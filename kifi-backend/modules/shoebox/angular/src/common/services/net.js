'use strict';

angular.module('kifi')

.factory('net', [
  'env', '$http', 'createExpiringCache',
  function (env, $http, createExpiringCache) {
    var shoebox = env.xhrBase;
    var search = env.xhrBaseSearch;
    var api = env.xhrBaseApi;
    var pathParamRe = /(:\w+)/;

    var post = angular.bind(null, http, 'POST');  // caller should pass any path params, optional post data (JSON), and an optional query params object
    var del = angular.bind(null, http, 'DELETE'); // caller should pass any path params and then, optionally, a query params object

    return {
      event: post(shoebox, '/events'),

      fetchPrefs: get(shoebox, '/user/prefs', 30),

      getLibraryInfos: get(shoebox, '/libraries', 30),
      getBasicLibraries: get(shoebox, '/users/:id/basicLibraries?offset=:offset&limit=:limit'),
      getLibraryInfoById: get(shoebox, '/libraries/:id/summary', 30),
      getLibraryByHandleAndSlug: get(shoebox, '/user-or-org/:handle/libraries/:slug?authToken=:authToken', 30),
      getLibraryById: get(shoebox, '/libraries/:id', 30),
      getKeepableLibraries: get(shoebox, '/keepableLibraries?includeOrgLibraries=true', 30),

      createLibrary: post(shoebox, '/libraries/add'),
      modifyLibrary: post(shoebox, '/libraries/:id/modify'),
      joinLibraries: post(shoebox, '/libraries/joinMultiple'),

      user: get(shoebox, '/user/:id', 30),
      getEmailInfo: get(shoebox, '/user/email'),
      userOrOrg: get(shoebox, '/user-or-org/:handle?authToken=:authToken', 30),
      createOrg: post(shoebox, '/organizations/create'),
      updateOrgProfile: post(shoebox, '/organizations/:id/modify'),
      uploadOrgAvatar: post(shoebox, '/organizations/:id/avatar/upload?x=:x&y=:y&s=:sideLength'),
      getOrgMembers: get(shoebox, '/organizations/:id/members', 30),
      getOrgLibraries: get(shoebox, '/organizations/:id/libraries', 30),
      getOrgBasicLibraries: get(shoebox, '/organizations/:id/basicLibraries?offset=:offset&limit=:limit'),
      sendOrgMemberInvite: post(shoebox, '/organizations/:id/members/invite'),
      sendOrgMemberInviteViaSlack: post(shoebox, '/organizations/:id/sendOrganizationInviteViaSlack'),
      declineOrgMemberInvite: post(shoebox, '/organizations/:id/members/invites/decline'),
      acceptOrgMemberInvite: post(shoebox, '/organizations/:id/members/invites/accept?authToken=:authToken'),
      cancelOrgMemberInvite: post(shoebox, '/organizations/:id/members/invites/cancel'),
      removeOrgMember: post(shoebox, '/organizations/:id/members/remove'),
      modifyOrgMember: post(shoebox, '/organizations/:id/members/modify'),
      suggestOrgMember: get(shoebox, '/organizations/:id/members/suggest?query=:query&limit=:limit'),
      transferOrgMemberOwnership: post(shoebox, '/organizations/:id/transfer'),
      getOrgSettings: get(shoebox, '/organizations/:id/featureSettings', 30),
      setOrgSettings: post(shoebox, '/organizations/:id/featureSettings'),
      blacklistBackfillWarning: post(shoebox, '/organizations/:id/blacklistBackfill'),
      blacklistBackfillDelete: post(shoebox, '/organizations/:id/blacklistBackfill'),
      mirrorComments: post(shoebox, '/organizations/:id/slack/mirrorComments?turnOn=:turnOn'),
      hideOrgDomain: post(shoebox, '/user/hideOrgDomain?orgId=:orgId'),

      getBillingState: get(shoebox, '/admin/billing/state?pubId=:pubId', 30),
      updateAccountState: post(shoebox, '/admin/billing/state?pubId=:pubId&newPlanId=:newPlanId&newCardId=:newCardId'),
      getBillingStatePreview: get(shoebox, '/admin/billing/state/preview?pubId=:pubId&newPlanId=:newPlanId&newCardId=:newCardId'),
      createNewCard: post(shoebox, '/admin/billing/card/add?pubId=:pubId'),
      getDefaultCard: get(shoebox, '/admin/billing/card/default?pubId=:pubId'),
      setDefaultCard: post(shoebox, '/admin/billing/card/default?pubId=:pubId'),
      getBillingContacts: get(shoebox, '/admin/billing/contacts?pubId=:pubId', 30),
      setBillingContacts: post(shoebox, '/admin/billing/contacts?pubId=:pubId'),
      getBillingEvents: get(shoebox, '/admin/billing/events?pubId=:pubId&limit=:limit&fromId=:fromId', 30),
      getBillingPlans: get(shoebox, '/admin/billing/plans?pubId=:pubId', 30),
      setBillingPlan: post(shoebox, '/admin/billing/plan?pubId=:pubId&planPubId=:planPubId'),
      getReferralCode: get(shoebox, '/organizations/:id/referralCode', 30),
      applyReferralCode: post(shoebox, '/organizations/:id/redeemCode'),
      getRewards: get(shoebox, '/organizations/:id/rewards', 30),

      getOrgDomains: get(shoebox, '/organizations/:id/getDomains', 30),
      addOrgDomain: post(shoebox, '/organizations/:id/addDomain'),
      addDomainAfterVerification: post(shoebox, '/organizations/:id/addDomainAfterVerification'),
      removeOrgDomain: post(shoebox, '/organizations/:id/removeDomain'),
      sendMemberConfirmationEmail: post(shoebox, '/organizations/:id/sendMemberConfirmationEmail'),

      // pass in limit={int} optionally in the separate query params object, since constructPath converts null param values into empty strings
      getKeepStream: get(shoebox, '/keeps/stream?beforeId=:beforeId&afterId=:afterId&filterKind=:filterKind&filterId=:filterId', 60),


      getKeep: get(shoebox, '/keeps/:id?authToken=:authToken'),
      getActivityForKeepId: get(shoebox, '/keeps/:keepId/activity'),
      getKeepsAtIntersection: post(api, '/1/pages/intersection'),
      modifyKeep: post(shoebox, '/keeps/:id/title'),
      getKeepsInLibrary: get(shoebox, '/libraries/:id/keeps', 30),
      addKeepsToLibrary: post(shoebox, '/libraries/:id/keeps'),
      copyKeepsToLibrary: post(shoebox, '/libraries/copy'),
      moveKeepsToLibrary: post(shoebox, '/libraries/move'),
      removeKeepFromLibrary: del(shoebox, '/libraries/:id/keeps/:keepId'),
      removeManyKeepsFromLibrary: post(shoebox, '/libraries/:id/keeps/delete'),
      changeKeepImage: post(shoebox, '/libraries/:id/keeps/:keepId/image'),
      exportOrgKeeps: post(shoebox, '/keeps/organizationExport'),
      getFtueLibraries: get(shoebox, '/libraries/marketing-suggestions'),
      checkLibraryForUpdates: get(shoebox, '/libraries/:id/updates?since=:since'),

      getLibraryShareSuggest: get(shoebox, '/libraries/:id/members/suggest?n=30', 30),
      updateLibraryMembership: post(shoebox, '/libraries/:id/members/:uid/access'),

      search: {
        search: get(search, '/search'),
        searched: post(search, '/search/events/searched'),
        resultClicked: post(search, '/search/events/resultClicked')
      },

      sendMobileAppSMS: post(shoebox, '/sms'),

      // left hand rail

      getInitialLeftHandRailInfo: get(shoebox, '/user/leftHandRail?numLibs=:numLibs'),

      // library slack integration
      modifyLibraryPushSlackIntegration: post(shoebox, '/libraries/:id/slack/push/:ltsId?turnOn=:turnOn'),
      modifyLibraryIngestSlackIntegration: post(shoebox, '/libraries/:id/slack/ingest/:stlId?turnOn=:turnOn'),
      deleteLibrarySlackIntegrations: post(shoebox, '/libraries/:id/slack/delete'),

      // slack
      getSlackIntegrationsForOrg: get(shoebox, '/organizations/:id/slack/list'),
      getKifiOrgsForSlackIntegration: get(shoebox, '/slack/add/organizations'),
      getAddSlackIntegrationLink: post(shoebox, '/libraries/:libraryId/slack/add'),
      publicSyncSlack: post(shoebox, '/organizations/:teamId/slack/sync/public'),
      privateSyncSlack: post(shoebox, '/organizations/:teamId/slack/sync/private'),
      connectSlack: post(shoebox, '/organizations/:teamId/slack/connect?slackTeamId=:optSlackTeamId&slackState=:optSlackState'),
      createTeamFromSlack: post(shoebox, '/organizations/create/slack?slackTeamId=:optSlackTeamId&slackState=:optSlackState'),
      togglePersonalDigest: post(shoebox, '/organizations/slack/togglePersonalDigest?slackTeamId=:slackTeamId&slackUserId=:slackUserId&turnOn=:turnOn'),

      modifyKeepRecipients: post(api, '/1/keeps/:id/recipients'),
      addMessageToKeepDiscussion: post(api, '/1/keeps/:id/messages'),
      getMessagesForKeepDiscussion: get(shoebox, '/keeps/:id/messages?limit=:limit&fromId=:fromId'), // ?limit={{number}}&fromId={{Option(String))}}
      deleteMessageFromKeepDiscussion: post(shoebox, '/keeps/:id/messages/delete'),
      markDiscussionAsRead: post(shoebox, '/keeps/markAsRead'),
      suggestRecipientsForKeep: get(api, '/1/keeps/suggestRecipients', 60),
      getPageContext: post(api, '/1/pages/context', 60),
      getPageInfo: post(api, '/1/pages/info', 60),

      // twitter
      twitterSync: post(shoebox, '/twitterSync'),

      updateLastSeenAnnouncement: post(shoebox, '/user/updateLastSeenAnnouncement')
    };

    function get(base, pathSpec, cacheSec) {
      if (!pathSpec) {
        throw new Error('You forgot to add a microservice name in net.js!');
      }

      var pathParts = pathSpec.split(pathParamRe);
      var cache = cacheSec && createExpiringCache(pathSpec, cacheSec);
      function doGet() {  // caller should pass any path params and then, optionally, a query params object
        return $http({
          method: 'GET',
          url: base + constructPath(pathParts, arguments),
          params: arguments[(pathParts.length - 1) / 2],
          cache: cache
        });
      }
      if (cache) {
        // Would also expose a method for removing a specific cache entry, but Angular
        // doesn't directly expose its functionality for building a request URL.
        // With our short-lived caches, clearing them entirely is good enough anyway.
        doGet.clearCache = angular.bind(cache, cache.removeAll);
      }
      return doGet;
    }

    function http(method, base, pathSpec) {
      var pathParts = pathSpec.split(pathParamRe);
      return function () {
        var path = constructPath(pathParts, arguments);
        var i = (pathParts.length - 1) / 2;
        var data = method === 'POST' ? arguments[i++] : undefined;
        var params = arguments[i++];
        return $http({
          method: method,
          url: base + path,
          params: params,
          data: data
        });
      };
    }

    function constructPath(pathParts, args) {
      if (pathParts.length === 1) {
        return pathParts[0];
      }
      var parts = pathParts.slice();
      for (var i = 1; i < pathParts.length; i += 2) {
        parts[i] = args[(i - 1) / 2];
      }
      return parts.join('');
    }
  }
]);
