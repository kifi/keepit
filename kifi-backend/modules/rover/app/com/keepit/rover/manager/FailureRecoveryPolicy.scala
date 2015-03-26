package com.keepit.rover.manager

import com.google.inject.{ Inject, Singleton }

@Singleton
class FailureRecoveryPolicy @Inject() () {
  def shouldRetry(url: String, error: Throwable, failureCount: Int): Boolean = error match {
    case _ => failureCount < 3 // todo(Léo): be smarter, use "do not scrape" rules here vs in ArticleFetchPolicy?
  }
}
