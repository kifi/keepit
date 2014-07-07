package com.keepit.abook

import com.keepit.common.db.Id
import com.keepit.model.{ABookInfo, ABookOriginType, User}
import scala.ref.WeakReference
import play.api.libs.json.JsValue
import com.google.inject.{Singleton, Provides, Inject}
import com.keepit.shoebox.{FakeShoeboxServiceModule}


class TestABookImporterPlugin @Inject() (contactsUpdater:ABookImporter) extends ABookImporterPlugin {
  def asyncProcessContacts(userId: Id[User], origin: ABookOriginType, aBookInfo: ABookInfo, s3Key: String, rawJsonRef: WeakReference[JsValue]): Unit = {
    contactsUpdater.processABookUpload(userId, origin, aBookInfo, s3Key, rawJsonRef)
  }
}

case class TestContactsUpdaterPluginModule() extends ContactsUpdaterPluginModule {
  @Provides
  @Singleton
  def contactsUpdaterPlugin(cUpdater:ABookImporter):ABookImporterPlugin = new TestABookImporterPlugin(cUpdater)
}
