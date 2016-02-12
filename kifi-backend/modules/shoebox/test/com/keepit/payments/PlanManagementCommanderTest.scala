package com.keepit.payments

import com.google.inject.Injector
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.model.PaidPlanFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.SpecificationLike

class PlanManagementCommanderTest extends SpecificationLike with ShoeboxTestInjector {
  val modules = Seq(
    FakeExecutionContextModule()
  )
  implicit val context = HeimdalContext.empty

  implicit def pubIdConfig(implicit injector: Injector) = inject[PublicIdConfiguration]

  "change paid plans" in {
    withDb(modules: _*) { implicit injector =>
      val (org, owner) = db.readWrite { implicit session =>
        val owner = UserFactory.user().saved
        val org = OrganizationFactory.organization().withOwner(owner).saved
        (org, owner)
      }

      val (restrictedPlan, currentConfig) = db.readWrite { implicit session =>
        val restrictedPlan = PaidPlanFactory.paidPlan().withEditableFeatures(Set(StaticFeature.EditOrganization)).saved
        val currentConfig = orgConfigRepo.getByOrgId(org.id.get)
        (restrictedPlan, currentConfig)
      }

      val featureSettingsToReset: Map[Feature, FeatureSetting] = Map( // random features with hopefully altered settings
        StaticFeature.PublishLibraries -> StaticFeatureSetting.DISABLED,
        StaticFeature.InviteMembers -> StaticFeatureSetting.ADMINS,
        StaticFeature.ForceEditLibraries -> StaticFeatureSetting.ADMINS
      )
      val featureSettingsToMaintain = Map(StaticFeature.EditOrganization -> StaticFeatureSetting.DISABLED)
      val alteredSettings = currentConfig.settings.setAll(featureSettingsToReset ++ featureSettingsToMaintain)
      currentConfig.settings !== alteredSettings // assert settings will actually change from old default
      restrictedPlan.defaultSettings !== alteredSettings // assert settings will actually change to new default

      orgCommander.setAccountFeatureSettings(OrganizationSettingsRequest(org.id.get, owner.id.get, alteredSettings))

      // upgrade plans, will reset org config to restrictedPlan.defaultSettings
      planManagementCommander.changePlan(org.id.get, restrictedPlan.id.get, ActionAttribution(None, None))
      val newConfig = db.readWrite { implicit session => orgConfigRepo.getByOrgId(org.id.get) }
      featureSettingsToReset.keys.map { feature =>
        newConfig.settings.settingFor(feature) === restrictedPlan.defaultSettings.settingFor(feature)
      }
      featureSettingsToMaintain.keys.map { feature =>
        newConfig.settings.settingFor(feature) === featureSettingsToMaintain.get(feature)
      }
      1 === 1
    }
  }
}
