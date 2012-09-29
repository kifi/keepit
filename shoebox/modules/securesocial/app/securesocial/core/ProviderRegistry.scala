/**
 * Copyright 2012 Jorge Aliss (jaliss at gmail dot com) - twitter: @jaliss
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package securesocial.core

import play.api.Logger

/**
 * A registry for all the providers.  Each provider is a plugin that will register itself here
 * at application start time.
 */
object ProviderRegistry {
  private var providers = Map[String, IdentityProvider]()

  def register(provider: IdentityProvider) {
//    if ( providers.contains(provider.providerId) ) {
//      throw new RuntimeException("There is already a provider registered for type: %s".format(provider.providerId))
//    }
    val p = (provider.providerId, provider)
    providers += p
    Logger.info("Registered Identity Provider: %s".format(provider.providerId))
  }

  def get(providerId: String) = providers.get(providerId)

  def all() = providers
}
