package com.keepit.social.providers

import play.api.Application
import com.keepit.social.UserIdentityProvider

class UsernamePasswordProvider(application: Application)
  extends securesocial.core.providers.UsernamePasswordProvider(application) with UserIdentityProvider
