package com.keepit.common.controller

import play.api.mvc._

import scala.concurrent.Future

case class PaginatedRequest[E, A](request: Request[A], items: Seq[E], page: Int) extends WrappedRequest[A](request)

trait PaginatedActions {

  object PaginatedPage {

    def zero[E](countItems: => Int, getItems: Int => Seq[E]): ActionBuilder[({ type L[T] = PaginatedRequest[E, T] })#L] = apply(countItems, getItems)(0)

    def apply[E](countItems: => Int, getItems: Int => Seq[E])(page: Int = 0): ActionBuilder[({ type L[T] = PaginatedRequest[E, T] })#L] = {
      new ActionBuilder[({ type L[T] = PaginatedRequest[E, T] })#L] {

        override def invokeBlock[A](request: Request[A], block: (PaginatedRequest[E, A]) => Future[Result]): Future[Result] = {
          val count = countItems
          val items = getItems(page)
          val paginatedRequest = PaginatedRequest[E, A](request, items, page)
          block(paginatedRequest)
        }

      }
    }

  }

}

