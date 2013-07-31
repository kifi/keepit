package com.keepit.common.admin

import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.keepit.common.zookeeper.{ServiceDiscovery, ServiceInstance, ServiceCluster}
import com.keepit.common.service.{ServiceType, ServiceStatus}
import com.keepit.common.amazon.{AmazonInstanceInfo}
import com.google.inject.Inject
import views.html
import com.keepit.common.logging.Logging
import play.api.mvc._

class ServiceController @Inject() (
    serviceDiscovery: ServiceDiscovery) extends Controller with Logging {

    def forceRefresh = Action { implicit request =>
        serviceDiscovery.forceUpdate()
        Ok("Alright, alright! I've refreshed.")
    }

    def myStatus = Action { implicit request =>
        Ok(serviceDiscovery.myStatus.map(_.name).getOrElse("none"))
    }

    def topology = Action { implicit request =>
        Ok(serviceDiscovery.toString)
    }

}
