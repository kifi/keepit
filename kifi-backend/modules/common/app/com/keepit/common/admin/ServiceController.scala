package com.keepit.common.admin

import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.keepit.common.zookeeper.{ServiceDiscovery, ServiceInstance, ServiceCluster}
import com.keepit.common.service.{ServiceType, ServiceStatus}
import com.keepit.common.amazon.{AmazonInstanceInfo}
import com.google.inject.{Inject, Singleton}
import views.html




@Singleton
class ServiceController @Inject() (
    actionAuthenticator: ActionAuthenticator,
    serviceDiscovery: ServiceDiscovery) extends AdminController(actionAuthenticator) {

    def forceRefresh = AdminHtmlAction { implicit request =>
        serviceDiscovery.forceUpdate()
        Ok("Alright, alright! I've refreshed.")
    }


}
