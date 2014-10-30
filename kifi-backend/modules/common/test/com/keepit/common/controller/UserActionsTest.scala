package com.keepit.common.controller

import com.google.inject.util.Providers
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.model.{ User, Username }
import com.keepit.test.CommonTestInjector
import play.api.mvc.{ Results, AnyContent, Controller }
import play.api.test.FakeRequest
import org.specs2.mutable._
import play.api.test._
import play.api.test.Helpers._
import com.keepit.common.db.Id

class UserActionsTest extends Specification with CommonTestInjector {

  val modules = Seq(
    FakeUserActionsModule(),
    FakeHttpClientModule()
  )

  def setUser()(implicit helper: FakeUserActionsHelper): Unit = {
    helper.setUser(com.keepit.model.User(id = Some(Id[User](1)), firstName = "Test", lastName = "User", username = Username("tuser"), normalizedUsername = "tuser"))
  }

  def unsetUser()(implicit helper: FakeUserActionsHelper): Unit = {
    helper.unsetUser()
  }

  class FakeController(val actionsHelper: UserActionsHelper) extends Controller with UserActions {
    val userActionsHelper = actionsHelper
  }

  "UserActions" should {
    "properly handle MaybeUserAction and UserAction" in {
      withInjector(modules: _*) { implicit injector =>
        implicit val actionsHelper = inject[UserActionsHelper].asInstanceOf[FakeUserActionsHelper]
        val Controller = new FakeController(actionsHelper) {
          def anyone = MaybeUserAction { request: MaybeUserRequest[AnyContent] =>
            request match {
              case request: UserRequest[_] =>
                Ok("user")
              case request: NonUserRequest[_] =>
                Ok("non-user")
            }
          }

          def usersOnly = UserAction {
            Ok
          }
        }

        { // MaybeUserAction behaves properly

          unsetUser()
          val anyReqF = Controller.anyone(FakeRequest())
          status(anyReqF) === OK
          contentAsString(anyReqF) === "non-user"

          setUser()
          val userReqF = Controller.anyone(FakeRequest())
          status(userReqF) === OK
          contentAsString(userReqF) === "user"

        }

        { // UserAction behaves properly
          unsetUser()
          val anyReqF = Controller.usersOnly(FakeRequest())
          status(anyReqF) === FORBIDDEN

          setUser()
          val userReqF = Controller.usersOnly(FakeRequest())
          status(userReqF) === OK
        }

        1 === 1
      }
    }
    "properly handle MaybeUserPage and UserPage" in {
      withInjector(modules: _*) { implicit injector =>
        implicit val actionsHelper = inject[UserActionsHelper].asInstanceOf[FakeUserActionsHelper]
        val Controller = new FakeController(actionsHelper) {
          def anyone = MaybeUserPage { request =>
            request match {
              case request: UserRequest[_] =>
                Ok("user")
              case request: NonUserRequest[_] =>
                Ok("non-user")
            }
          }

          def usersOnly = UserPage { request =>
            Ok
          }
        }

        { // MaybeUserPage behaves properly
          unsetUser()
          val anyReqF = Controller.anyone(FakeRequest())
          status(anyReqF) === OK
          contentAsString(anyReqF) === "non-user"

          setUser()
          val userReqF = Controller.anyone(FakeRequest())
          status(userReqF) === OK
          contentAsString(userReqF) === "user"

        }

        { // UserPage behaves properly
          unsetUser()
          val anyReqF = Controller.usersOnly(FakeRequest())
          status(anyReqF) === SEE_OTHER

          setUser()
          val userReqF = Controller.usersOnly(FakeRequest())
          status(userReqF) === OK
        }
      }
    }
  }
}
