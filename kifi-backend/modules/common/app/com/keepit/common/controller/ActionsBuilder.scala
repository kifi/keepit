package com.keepit.common.controller

import com.keepit.common.concurrent.ExecutionContext
import securesocial.core.{SecureSocial, SecuredRequest}
import scala.concurrent.Future
import play.api.mvc._
import play.api.libs.json.{JsValue, JsNumber}
import play.api.i18n.Messages
import securesocial.core.SecuredRequest
import play.api.mvc.SimpleResult

trait ActionsBuilder { self: Controller =>
  val actionAuthenticator: ActionAuthenticator

  object Actions extends ActionsBuilder0(actionAuthenticator)

}

class ActionsBuilder0(actionAuthenticator: ActionAuthenticator) extends Controller {
  private implicit val ec = ExecutionContext.immediate

  private def SocialPlaceholder[T]: SecuredRequest[T] => Future[SimpleResult] = null
  private def UnauthPlaceholder[T]: Request[T] => Future[SimpleResult] = null

  def unhandledUnAuthenticated[T](apiClient: Boolean) = { implicit request: Request[T] =>
    if (apiClient) {
      Future.successful(Forbidden(JsNumber(0)))
    } else {
      Future.successful(Redirect("/login")
        .flashing("error" -> Messages("securesocial.loginRequired"))
        .withSession(session + (SecureSocial.OriginalUrlKey -> request.uri)))
    }
  }

  private def ActionHandlerAsync[T](parser: BodyParser[T], apiClient: Boolean, allowPending: Boolean, authFilter: AuthenticatedRequest[T] => Boolean)
                                   (onAuthenticated: AuthenticatedRequest[T] => Future[SimpleResult], onSocialAuthenticated: SecuredRequest[T] => Future[SimpleResult] = unhandledUnAuthenticated[T](apiClient), onUnauthenticated: Request[T] => Future[SimpleResult] = unhandledUnAuthenticated[T](apiClient)): Action[T] = {

    val filteredAuthenticatedRequest: AuthenticatedRequest[T] => Future[SimpleResult] = { request =>
      if (authFilter(request)) {
        onAuthenticated(request)
      } else {
        onUnauthenticated(request)
      }

    }

    actionAuthenticator.authenticatedAction(apiClient, allowPending, parser, filteredAuthenticatedRequest, onUnauthenticated = onUnauthenticated, onSocialAuthenticated = onSocialAuthenticated)
  }

  trait ActionDefaults {
    val contentTypeOpt: Option[String] = None
    val apiClient: Boolean = true
    val allowPending: Boolean = false
    def globalAuthFilter[T](request: AuthenticatedRequest[T]) = true
  }

  trait AuthenticatedActions extends ActionDefaults {

    // The type is what the Content Type header is set as.

    def authenticatedAsync[T](parser: BodyParser[T] = parse.anyContent, apiClient: Boolean = apiClient, allowPending: Boolean = allowPending, authFilter: (AuthenticatedRequest[T] => Boolean) = globalAuthFilter[T] _)(authenticatedAction: AuthenticatedRequest[T] => Future[SimpleResult]): Action[T] = {
      contentTypeOpt match {
        case Some(contentType) =>
          ActionHandlerAsync(parser, apiClient, allowPending, authFilter)(authenticatedAction.andThen(_.map(_.as(contentType))))
        case None =>
          ActionHandlerAsync(parser, apiClient, allowPending, authFilter)(authenticatedAction)
      }
    }

    def authenticatedAsync(authenticatedAction: AuthenticatedRequest[AnyContent] => Future[SimpleResult]): Action[AnyContent] = {
      authenticatedAsync()(authenticatedAction)
    }

    def authenticated[T](parser: BodyParser[T] = parse.anyContent, apiClient: Boolean = apiClient, allowPending: Boolean = allowPending)(authenticatedAction: AuthenticatedRequest[T] => SimpleResult): Action[T] = {
      authenticatedAsync(parser, apiClient, allowPending)(authenticatedAction.andThen(Future.successful))
    }

    def authenticated(authenticatedAction: AuthenticatedRequest[AnyContent] => SimpleResult): Action[AnyContent] = {
      authenticated()(authenticatedAction)
    }

    def authenticatedParseJsonAsync(authenticatedAction: AuthenticatedRequest[JsValue] => Future[SimpleResult]): Action[JsValue] = {
      authenticatedAsync(parser = parse.tolerantJson)(authenticatedAction)
    }

    def authenticatedParseJson(authenticatedAction: AuthenticatedRequest[JsValue] => SimpleResult): Action[JsValue] = {
      authenticated(parser = parse.tolerantJson)(authenticatedAction)
    }

  }

  trait NonAuthenticatedActions extends ActionDefaults {
    val contentTypeOpt: Option[String]
    val apiClient: Boolean
    val allowPending: Boolean
    // The type is what the Content Type header is set as.

    def async[T](parser: BodyParser[T] = parse.anyContent, apiClient: Boolean = apiClient, allowPending: Boolean = allowPending, authFilter: AuthenticatedRequest[T] => Boolean = globalAuthFilter[T] _)(authenticatedAction: AuthenticatedRequest[T] => Future[SimpleResult], unauthenticatedAction: Request[T] => Future[SimpleResult]): Action[T] = {
      contentTypeOpt match {
        case Some(contentType) =>
          ActionHandlerAsync(parser, apiClient, allowPending, authFilter)(authenticatedAction.andThen(_.map(_.as(contentType))), unauthenticatedAction.andThen(_.map(_.as(contentType))))
        case None =>
          ActionHandlerAsync(parser, apiClient, allowPending, authFilter)(authenticatedAction, unauthenticatedAction)
      }
    }

    def async(authenticatedAction: AuthenticatedRequest[AnyContent] => Future[SimpleResult], unauthenticatedAction: Request[AnyContent] => Future[SimpleResult]): Action[AnyContent] = {
      async()(authenticatedAction, unauthenticatedAction)
    }

    def apply[T](parser: BodyParser[T] = parse.anyContent, apiClient: Boolean = apiClient, allowPending: Boolean = allowPending)(authenticatedAction: AuthenticatedRequest[T] => SimpleResult, unauthenticatedAction: Request[T] => SimpleResult): Action[T] = {
      async(parser, apiClient, allowPending)(authenticatedAction.andThen(Future.successful), unauthenticatedAction.andThen(Future.successful))
    }

    def apply(authenticatedAction: AuthenticatedRequest[AnyContent] => SimpleResult, unauthenticatedAction: Request[AnyContent] => SimpleResult): Action[AnyContent] = {
      apply()(authenticatedAction, unauthenticatedAction)
    }

    def parseJsonAsync(authenticatedAction: AuthenticatedRequest[JsValue] => Future[SimpleResult], unauthenticatedAction: Request[JsValue] => Future[SimpleResult]): Action[JsValue] = {
      async(parser = parse.tolerantJson)(authenticatedAction, unauthenticatedAction)
    }

    def parseJson(authenticatedAction: AuthenticatedRequest[JsValue] => SimpleResult, unauthenticatedAction: Request[JsValue] => SimpleResult): Action[JsValue] = {
      apply(parser = parse.tolerantJson)(authenticatedAction, unauthenticatedAction)
    }
  }
}
