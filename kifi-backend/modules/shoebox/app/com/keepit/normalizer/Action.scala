package com.keepit.normalizer

sealed trait Action
case object Accept extends Action
case object Reject extends Action
case class Check(contentCheck: ContentCheck) extends Action

object Action {
  def apply(currentReference: NormalizationReference, contentChecks: Seq[ContentCheck])(candidate: NormalizationCandidate): Action = candidate match {
    case _: VerifiedCandidate => Accept
    case _ => contentChecks.find(_.isDefinedAt(candidate)).map(Check).getOrElse(Reject)
  }
}
