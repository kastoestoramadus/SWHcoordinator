package coordinator

import spray.json.DefaultJsonProtocol

case class CalendarArgs(fb_token: String, meetup_id: String, city: String, date_from: String, date_to: String)

object MyJsonProtocol extends DefaultJsonProtocol {
  implicit val calendarArgsFormat = jsonFormat5(CalendarArgs)
}
