
object ServiceRouter {
  // A static object so that we can use this easily anywhere inside of Play

  def apply(key: String): String = {

    key match {
      case "logout" => "/logout"
    }
  }
}