package com.keepit.common.controller

import com.keepit.common.concurrent.ExecutionContext
import securesocial.core.{ SecureSocial, SecuredRequest }
import scala.concurrent.Future
import play.api.mvc._
import play.api.libs.json.{ JsValue, JsNumber }
import play.api.i18n.Messages
import securesocial.core.SecuredRequest

import play.api.mvc.Result
import com.keepit.common.logging.Logging

trait ActionsBuilder { self: Controller =>
  val actionAuthenticator: ActionAuthenticator

  object Actions extends ActionsBuilder0(actionAuthenticator)

}

class ActionsBuilder0(actionAuthenticator: ActionAuthenticator) extends Controller with Logging {
  private implicit val ec = ExecutionContext.immediate

  def unhandledUnAuthenticated[T](tag: String, apiClient: Boolean) = {
    val p = { implicit request: Request[T] =>
      log.warn(s"tag:$tag api:$apiClient - UnAuthenticated request on access attempt to ${request.method}:${request.path} with cookies:${request.cookies.mkString(",")}")
      if (apiClient) {
        Future.successful(Forbidden(JsNumber(0)))
      } else {
        Future.successful(Redirect("/login")
          .flashing("error" -> Messages("securesocial.loginRequired"))
          .withSession(request2session + (SecureSocial.OriginalUrlKey -> request.uri)))
      }
    }
    p
  }

  private def ActionHandlerAsync[T](parser: BodyParser[T], apiClient: Boolean, allowPending: Boolean, authFilter: AuthenticatedRequest[T] => Boolean)(onAuthenticated: AuthenticatedRequest[T] => Future[Result],
    onSocialAuthenticated: SecuredRequest[T] => Future[Result] = unhandledUnAuthenticated[T]("onSocial", apiClient),
    onUnauthenticated: Request[T] => Future[Result] = unhandledUnAuthenticated[T]("onUnauth", apiClient)): Action[T] = {

    val filteredAuthenticatedRequest: AuthenticatedRequest[T] => Future[Result] = { request =>
      if (authFilter(request)) {
        onAuthenticated(request)
      } else {
        onUnauthenticated(request)
      }
    }

    actionAuthenticator.authenticatedAction(apiClient, allowPending, parser, filteredAuthenticatedRequest,
      onSocialAuthenticated = onSocialAuthenticated, onUnauthenticated = onUnauthenticated)
  }

  trait ActionDefaults {
    val contentTypeOpt: Option[String] = None
    val apiClient: Boolean = true
    val allowPending: Boolean = false
    def globalAuthFilter[T](request: AuthenticatedRequest[T]) = true
  }

  trait AuthenticatedActions extends ActionDefaults {
    // The type is what the Content Type header is set as.

    def authenticatedAsync[T](parser: BodyParser[T] = parse.anyContent, apiClient: Boolean = apiClient, allowPending: Boolean = allowPending, authFilter: (AuthenticatedRequest[T] => Boolean) = globalAuthFilter[T] _)(authenticatedAction: AuthenticatedRequest[T] => Future[Result]): Action[T] = {
      contentTypeOpt match {
        case Some(contentType) =>
          ActionHandlerAsync(parser, apiClient, allowPending, authFilter)(onAuthenticated = authenticatedAction.andThen(_.map(_.as(contentType))))
        case None =>
          ActionHandlerAsync(parser, apiClient, allowPending, authFilter)(onAuthenticated = authenticatedAction)
      }
    }

    def authenticatedAsync(authenticatedAction: AuthenticatedRequest[AnyContent] => Future[Result]): Action[AnyContent] = {
      authenticatedAsync[AnyContent]()(authenticatedAction)
    }

    def authenticated[T](parser: BodyParser[T] = parse.anyContent, apiClient: Boolean = apiClient, allowPending: Boolean = allowPending)(authenticatedAction: AuthenticatedRequest[T] => Result): Action[T] = {
      authenticatedAsync(parser, apiClient, allowPending)(authenticatedAction.andThen(Future.successful))
    }

    def authenticated(authenticatedAction: AuthenticatedRequest[AnyContent] => Result): Action[AnyContent] = {
      authenticated[AnyContent]()(authenticatedAction)
    }

    def authenticatedParseJsonAsync(authenticatedAction: AuthenticatedRequest[JsValue] => Future[Result]): Action[JsValue] = {
      authenticatedAsync(parser = parse.tolerantJson)(authenticatedAction)
    }

    def authenticatedParseJson(authenticatedAction: AuthenticatedRequest[JsValue] => Result): Action[JsValue] = {
      authenticated(parser = parse.tolerantJson)(authenticatedAction)
    }

    def authenticatedParseJsonAsync(apiClient: Boolean = apiClient, allowPending: Boolean = allowPending)(authenticatedAction: AuthenticatedRequest[JsValue] => Future[Result]): Action[JsValue] = {
      authenticatedAsync(parser = parse.tolerantJson, apiClient = apiClient, allowPending = allowPending)(authenticatedAction)
    }

    def authenticatedParseJson(apiClient: Boolean = apiClient, allowPending: Boolean = allowPending)(authenticatedAction: AuthenticatedRequest[JsValue] => Result): Action[JsValue] = {
      authenticated(parser = parse.tolerantJson, apiClient = apiClient, allowPending = allowPending)(authenticatedAction)
    }

  }

  trait NonAuthenticatedActions extends ActionDefaults {
    // The type is what the Content Type header is set as.

    def async[T](parser: BodyParser[T] = parse.anyContent, apiClient: Boolean = apiClient, allowPending: Boolean = allowPending, authFilter: AuthenticatedRequest[T] => Boolean = globalAuthFilter[T] _)(authenticatedAction: AuthenticatedRequest[T] => Future[Result], unauthenticatedAction: Request[T] => Future[Result]): Action[T] = {
      contentTypeOpt match {
        case Some(contentType) =>
          ActionHandlerAsync(parser, apiClient, allowPending, authFilter)(onAuthenticated = authenticatedAction.andThen(_.map(_.as(contentType))), onUnauthenticated = unauthenticatedAction.andThen(_.map(_.as(contentType))), onSocialAuthenticated = unauthenticatedAction.andThen(_.map(_.as(contentType))))
        case None =>
          ActionHandlerAsync(parser, apiClient, allowPending, authFilter)(onAuthenticated = authenticatedAction, onUnauthenticated = unauthenticatedAction, onSocialAuthenticated = unauthenticatedAction)
      }
    }

    def async(authenticatedAction: AuthenticatedRequest[AnyContent] => Future[Result], unauthenticatedAction: Request[AnyContent] => Future[Result]): Action[AnyContent] = {
      async[AnyContent]()(authenticatedAction, unauthenticatedAction)
    }

    def apply[T](parser: BodyParser[T] = parse.anyContent, apiClient: Boolean = apiClient, allowPending: Boolean = allowPending)(authenticatedAction: AuthenticatedRequest[T] => Result, unauthenticatedAction: Request[T] => Result): Action[T] = {
      async(parser, apiClient, allowPending)(authenticatedAction.andThen(Future.successful), unauthenticatedAction.andThen(Future.successful))
    }

    def apply(authenticatedAction: AuthenticatedRequest[AnyContent] => Result, unauthenticatedAction: Request[AnyContent] => Result): Action[AnyContent] = {
      apply[AnyContent]()(authenticatedAction, unauthenticatedAction)
    }

    def parseJsonAsync(authenticatedAction: AuthenticatedRequest[JsValue] => Future[Result], unauthenticatedAction: Request[JsValue] => Future[Result]): Action[JsValue] = {
      async(parser = parse.tolerantJson)(authenticatedAction, unauthenticatedAction)
    }

    def parseJson(authenticatedAction: AuthenticatedRequest[JsValue] => Result, unauthenticatedAction: Request[JsValue] => Result): Action[JsValue] = {
      apply(parser = parse.tolerantJson)(authenticatedAction, unauthenticatedAction)
    }

    def parseJsonAsync(apiClient: Boolean = apiClient, allowPending: Boolean = allowPending)(authenticatedAction: AuthenticatedRequest[JsValue] => Future[Result], unauthenticatedAction: Request[JsValue] => Future[Result]): Action[JsValue] = {
      async(parser = parse.tolerantJson, apiClient = apiClient, allowPending = allowPending)(authenticatedAction, unauthenticatedAction)
    }

    def parseJson(apiClient: Boolean = apiClient, allowPending: Boolean = allowPending)(authenticatedAction: AuthenticatedRequest[JsValue] => Result, unauthenticatedAction: Request[JsValue] => Result): Action[JsValue] = {
      apply(parser = parse.tolerantJson, apiClient = apiClient, allowPending = allowPending)(authenticatedAction, unauthenticatedAction)
    }
  }
}
