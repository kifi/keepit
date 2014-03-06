package com.keepit.common.zookeeper

import com.keepit.common.amazon._
import com.keepit.common.service._
import org.specs2.mutable.Specification
import play.api.libs.json.Json

class RemoteServiceTest extends Specification {

  val instance = new AmazonInstanceInfo(
    instanceId = AmazonInstanceId("i-f168c1a8"),
    name = Some("some-name"),
    service = Some("some-service"),
    localHostname = "ip-10-160-95-26.us-west-1.compute.internal",
    publicHostname = "ec2-50-18-183-73.us-west-1.compute.amazonaws.com",
    localIp = IpAddress("10.160.95.26"),
    publicIp = IpAddress("50.18.183.73"),
    instanceType = "c1.medium",
    availabilityZone = "us-west-1b",
    securityGroups = "default",
    amiId = "ami-1bf9de5e",
    amiLaunchIndex = "0",
    loadBalancer = Some("some-elb")
  )

  "RemoteService" should {
    "be serialized and deserialized" in {
      val remoteService = RemoteService(instance, ServiceStatus.UP, ServiceType.TEST_MODE)
      val json = Json.toJson(remoteService)
      json === Json.parse("""{"amazonInstanceInfo":{"instanceId":{"id":"i-f168c1a8"},"name":"some-name","service":"some-service","localHostname":"ip-10-160-95-26.us-west-1.compute.internal","publicHostname":"ec2-50-18-183-73.us-west-1.compute.amazonaws.com","localIp":{"ip":"10.160.95.26"},"publicIp":{"ip":"50.18.183.73"},"instanceType":"c1.medium","availabilityZone":"us-west-1b","securityGroups":"default","amiId":"ami-1bf9de5e","amiLaunchIndex":"0","loadBalancer":"some-elb","tags":{}},"status":"up","serviceType":"TEST_MODE"}""")
      val fromJson = Json.fromJson[RemoteService](json).get
      fromJson === remoteService
    }
    "be deserialized and serialized" in {
      val json = Json.parse("""{"amazonInstanceInfo":{"instanceId":{"id":"i-ea0e8ab7"},"localHostname":"ip-10-166-30-185.us-west-1.compute.internal","publicHostname":"ec2-50-18-71-24.us-west-1.compute.amazonaws.com","localIp":{"ip":"10.166.30.185"},"publicIp":{"ip":"50.18.71.24"},"instanceType":"c1.xlarge","availabilityZone":"us-west-1b","securityGroups":"default","amiId":"ami-a82315ed","amiLaunchIndex":"0","tags":{}},"status":"up","serviceType":"SEARCH"}""")
      val remoteService = Json.fromJson[RemoteService](json).get
      val jsonAgain = Json.toJson(remoteService)
      jsonAgain === json
    }

  }
}
