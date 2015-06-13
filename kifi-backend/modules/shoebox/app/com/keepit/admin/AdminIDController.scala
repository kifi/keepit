package com.keepit.controllers.admin

import com.google.inject.{ Singleton, Inject }
import com.keepit.common.controller.{ AdminUserActions, UserActionsHelper }
import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId, PublicIdRegistry }
import com.keepit.common.db.Id
import com.keepit.model.{ Organization, Library }
import scala.util.Try

@Singleton
class AdminIDController @Inject() (
    val userActionsHelper: UserActionsHelper,
    implicit val publicIdConfig: PublicIdConfiguration) extends AdminUserActions {

  val warmUp = {
    Library.publicId(Id(1))
    Organization.publicId(Id(1))
  }

  def listAll = AdminUserPage { implicit request =>
    Ok(registry.mkString("\n"))
  }

  def byId(name: String, id: Long) = AdminUserPage { implicit request =>
    val pubId = PublicIdRegistry.registry.find(_._1.toLowerCase.contains(name.toLowerCase)).map {
      case (clazz, accessor) =>
        clazz + " " + accessor.toPubId(id)
    }.getOrElse("Couldn't find class")
    Ok(pubId)
  }

  def byPublicId(name: String, publicId: String) = AdminUserPage { implicit request =>
    val id = PublicIdRegistry.registry.find(_._1.toLowerCase.contains(name.toLowerCase)).map {
      case (clazz, accessor) =>
        clazz + " " + Try(accessor.toId(publicId)).toOption.getOrElse("(invalid)")
    }.getOrElse("Couldn't find class")
    Ok(id)
  }

  private def registry: Seq[(String, String, Long)] = {
    PublicIdRegistry.registry.map {
      case (companion, accessor) =>

        val a = accessor.toPubId(1)
        val b = accessor.toId(a)

        println(companion + " " + a + " " + b)
        (companion, a, b)
    }
  }

}

