package app.logorrr.views

import org.scalatest.wordspec.AnyWordSpecLike

class LogColumnDefSpec extends AnyWordSpecLike {

  "LogColumnDefinition" should {
    "work" in {
      LogColumnDef().parse("")
    }
  }
}
