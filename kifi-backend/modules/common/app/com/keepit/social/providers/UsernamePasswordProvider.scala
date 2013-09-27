package com.keepit.social.providers

import com.keepit.social.UserIdentityProvider

import play.api.Application

class UsernamePasswordProvider(application: Application)
  extends securesocial.core.providers.UsernamePasswordProvider(application) with UserIdentityProvider
