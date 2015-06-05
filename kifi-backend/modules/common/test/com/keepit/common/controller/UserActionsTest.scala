package com.keepit.common.controller

import com.keepit.common.controller.KifiSession.HttpSessionWrapper
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.model.{ PrimaryUsername, User, Username }
import com.keepit.test.CommonTestInjector
import play.api.mvc.{ AnyContent, Controller }
import play.api.test.FakeRequest
import org.specs2.mutable._
import play.api.test.Helpers._
import com.keepit.common.db.Id

class UserActionsTest extends Specification with CommonTestInjector {

  val modules = Seq(
    FakeUserActionsModule(),
    FakeHttpClientModule()
  )

  def setUser()(implicit helper: FakeUserActionsHelper): Unit = {
    val primaryUsername = PrimaryUsername(original = Username("tuser"), normalized = Username("tuser"))
    helper.setUser(com.keepit.model.User(id = Some(Id[User](1)), firstName = "Test", lastName = "User", primaryUsername = Some(primaryUsername)))
  }

  def unsetUser()(implicit helper: FakeUserActionsHelper): Unit = {
    helper.unsetUser()
  }

  class FakeController(val actionsHelper: UserActionsHelper) extends Controller with UserActions {
    val userActionsHelper = actionsHelper
  }

  "HttpSessionWrapper" should {
    "parse ids" in {
      new HttpSessionWrapper(null).parseUserId("123") === Id[User](123)
      new HttpSessionWrapper(null).parseUserId("Some(123)") === Id[User](123)
    }
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
          headers(anyReqF).get("Location") === Some("/login")

          setUser()
          val userReqF = Controller.usersOnly(FakeRequest())
          status(userReqF) === OK
        }
      }
    }
  }
}
