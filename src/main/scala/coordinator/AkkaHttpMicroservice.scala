package coordinator

import java.io.IOException

import akka.actor.ActorSystem
import akka.event.{Logging, LoggingAdapter}
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.{ResponseEntity, HttpRequest, HttpResponse}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.stream.{ActorMaterializer, Materializer}
import com.typesafe.config.{Config, ConfigFactory}
import spray.json.DefaultJsonProtocol

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.math._

case class IpInfo(ip: String, country_name: Option[String], city: Option[String], latitude: Option[Double], longitude: Option[Double])

case class IpPairSummaryRequest(ip1: String, ip2: String)

case class IpPairSummary(distance: Option[Double], ip1Info: IpInfo, ip2Info: IpInfo)

object IpPairSummary {
  def apply(ip1Info: IpInfo, ip2Info: IpInfo): IpPairSummary = IpPairSummary(calculateDistance(ip1Info, ip2Info), ip1Info, ip2Info)

  private def calculateDistance(ip1Info: IpInfo, ip2Info: IpInfo): Option[Double] = {
    (ip1Info.latitude, ip1Info.longitude, ip2Info.latitude, ip2Info.longitude) match {
      case (Some(lat1), Some(lon1), Some(lat2), Some(lon2)) =>
        // see http://www.movable-type.co.uk/scripts/latlong.html
        val φ1 = toRadians(lat1)
        val φ2 = toRadians(lat2)
        val Δφ = toRadians(lat2 - lat1)
        val Δλ = toRadians(lon2 - lon1)
        val a = pow(sin(Δφ / 2), 2) + cos(φ1) * cos(φ2) * pow(sin(Δλ / 2), 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        Option(EarthRadius * c)
      case _ => None
    }
  }

  private val EarthRadius = 6371.0
}

trait Protocols extends DefaultJsonProtocol {
  implicit val ipInfoFormat = jsonFormat5(IpInfo.apply)
  implicit val ipPairSummaryRequestFormat = jsonFormat2(IpPairSummaryRequest.apply)
  implicit val ipPairSummaryFormat = jsonFormat3(IpPairSummary.apply)
}

trait Service extends Protocols {
  implicit val system: ActorSystem
  implicit def executor: ExecutionContextExecutor
  implicit val materializer: Materializer

  def config: Config
  val logger: LoggingAdapter

  lazy val freeGeoIpConnectionFlow: Flow[HttpRequest, HttpResponse, Any] =
    Http().outgoingConnection(config.getString("services.freeGeoIpHost"), config.getInt("services.freeGeoIpPort"))

  lazy val meetupConnectionFlow: Flow[HttpRequest, HttpResponse, Any] =
    Http().outgoingConnection(config.getString("services.meetupEndpointHost"), config.getInt("services.meetupEndpointPort"))

  lazy val facebookConnectionFlow: Flow[HttpRequest, HttpResponse, Any] =
    Http().outgoingConnection(config.getString("services.facebookEndpointHost"), config.getInt("services.facebookEndpointPort"))

  import MyJsonProtocol.calendarArgsFormat

  def meetupEndpointRequest(request: HttpRequest): Future[HttpResponse] = Source.single(request).via(meetupConnectionFlow).runWith(Sink.head)
  def facebookEndpointRequest(request: HttpRequest): Future[HttpResponse] = Source.single(request).via(facebookConnectionFlow).runWith(Sink.head)
  def freeGeoIpRequest(request: HttpRequest): Future[HttpResponse] = Source.single(request).via(freeGeoIpConnectionFlow).runWith(Sink.head)

  def issueRequest(getResponseAction: Function[HttpRequest, Future[HttpResponse]], request: HttpRequest, info: String): Future[ResponseEntity] = {
    getResponseAction(request).map { response =>
      response.status match {
        case OK => response.entity
        case _ =>
          val msg = s"Request to $info failed with status ${response.status}"
          logger.error(msg)
          throw new Exception(msg)
      }
    }
  }

  def getMeetupProfile(fbToken: String): Future[String] = {
    val request = RequestBuilding.Get(s"/profile?meetup_id=$fbToken")
    issueRequest(facebookEndpointRequest, request, "meetup profile").flatMap({ response =>
      Unmarshal(response).to[String]
    })
  }

  def getFbEvents(fbToken: String, fbProfile: String, meetupProfile: String, city: String, dateFrom: String, dateTo: String): Future[String] = {
    val request = RequestBuilding.Get(s"/events?fb_token=$fbToken&fb_profile=$fbProfile&meetup_profile=$meetupProfile&city=$city&date_from=$dateFrom&date_to=$dateTo")
    issueRequest(facebookEndpointRequest, request, "fb events").flatMap({ response =>
      Unmarshal(response).to[String]
    })
  }

  def getMeetupEvents(city: String, since: String, to: String): Future[String] = {
    val request = RequestBuilding.Get(s"/events?city=$city&since=$since&to=$to")
    issueRequest(meetupEndpointRequest, request, "meetup events").flatMap({ response =>
      Unmarshal(response).to[String]
    })
  }

  def getClassifiedEvents(meetupProfile: String): Future[String] = {
    ???
  }

  case class MeetupProfile(meetup_profile: String)
  case class FacebookProfile(fb_profile: String)

  def getFbProfile(fbToken: String): Future[String] = {
    val request = RequestBuilding.Get(s"/profile?fb_token=$fbToken")
    issueRequest(facebookEndpointRequest, request, "fb profile").flatMap({ response =>
      val result = Unmarshal(response).to[String]
      result.map(_.replaceAll("\\n", ""))
    })
  }

  val routes = logRequestResult("akka-http-microservice") {
    pathPrefix("calendar") {
      (post & entity(as[CalendarArgs])) { calendarArgs =>
        complete {
          val fbProfile = getFbProfile(calendarArgs.fb_token)
          fbProfile.map({ fbProfile =>
            logger.info(s"--------- Result from Facebok profile endpoint: $fbProfile")
            val meetupProfile = "meetupProfile"
            /* val fbEvents = getFbEvents(calendarArgs.fb_token, fbProfile, meetupProfile, calendarArgs.city, calendarArgs.date_from, calendarArgs.date_to)
            fbEvents.map({ events =>
               logger.info(s"---------- Result from Facebok events endpoint: $events")
            })*/

            val meetupEvents = getMeetupEvents(calendarArgs.city, calendarArgs.date_from, calendarArgs.date_to)
            meetupEvents.map({ events =>
              // logger.info(s"---------- Result from meetup events endpoint: $events")
            })

            meetupEvents
          })
        }
      }
    }
  }
}

object AkkaHttpMicroservice extends App with Service {
  override implicit val system = ActorSystem()
  override implicit val executor = system.dispatcher
  override implicit val materializer = ActorMaterializer()

  override val config = ConfigFactory.load()
  override val logger = Logging(system, getClass)

  Http().bindAndHandle(routes, config.getString("http.interface"), config.getInt("http.port"))
}
