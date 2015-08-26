package com.keepit.controllers.admin

import com.keepit.common.controller.{ AdminUserActions, UserActionsHelper }
import com.keepit.common.db.slick.Database
import com.keepit.model.{ OrganizationRepo, OrganizationStates }
import com.keepit.payments._
import com.keepit.common.akka.SafeFuture

import play.api.libs.iteratee.{ Concurrent, Enumerator }

import scala.concurrent.ExecutionContext
import scala.util.{ Success, Failure }

import com.google.inject.Inject

class AdminPaymentsController @Inject() (
    val userActionsHelper: UserActionsHelper,
    implicit val executionContext: ExecutionContext,
    organizationRepo: OrganizationRepo,
    paidAccountRepo: PaidAccountRepo,
    planCommander: PlanManagementCommander,
    db: Database) extends AdminUserActions {

  def backfillPaidAccounts = AdminUserAction { request =>
    def printStackTraceToChannel(t: Throwable, channel: Concurrent.Channel[String]) = {
      val stackTrace = t.getStackTrace().toSeq
      stackTrace.foreach { ste =>
        channel.push(s">> ${ste.toString}\n")
      }
    }

    def processAndReport(channel: Concurrent.Channel[String]) = {
      val orgs = db.readOnlyMaster { implicit session =>
        organizationRepo.all()
      }.filter(_.state == OrganizationStates.ACTIVE)
      channel.push(s"All ${orgs.size} organizations loaded\n")
      orgs.foreach { org =>
        channel.push("----------------------------------------\n")
        channel.push(s"Processing org ${org.id.get}: ${org.name}\n")
        db.readWrite { implicit session =>
          paidAccountRepo.maybeGetByOrgId(org.id.get) match {
            case Some(_) => {
              channel.push(s"Paid account already exists. Doing nothing.\n")
              Thread.sleep(200)
            }
            case None => {
              planCommander.createAndInitializePaidAccountForOrganization(org.id.get, PaidPlan.DEFAULT, request.userId, session) match {
                case Success(event) => {
                  channel.push(s"Successfully created paid account for org ${org.id.get}\n")
                  channel.push(event.toString)
                }
                case Failure(ex) => {
                  channel.push(s"Failed creating paid account for org ${org.id.get}: ${ex.getMessage}\n")
                  printStackTraceToChannel(ex, channel)
                }
              }
              Thread.sleep(300)
            }
          }
        }
      }
    }

    val enum: Enumerator[String] = Concurrent.unicast(onStart = (channel: Concurrent.Channel[String]) =>
      SafeFuture("Paid Account Backfill") {
        processAndReport(channel)
        channel.eofAndEnd()
      }
    )
    Ok.chunked(enum)
  }

}
