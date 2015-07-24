***API Readme***

APIs:

GET /site/organizations/:id
  out: {“organization”: OrganizationInfo, “membership”: MembershipInfo } }

GET /site/user/:id/organizations
  out: {“organizations”: Array[OrganizationCard]}

POST /site/organizations/create
  in:  OrganizationInitialValues
  out: {“organization”: OrganizationInfo, “membership”: MembershipInfo }

POST /site/organizations/:id/modify
  in: OrganizationModifications
  out: {“organization”: OrganizationInfo, “membership”: MembershipInfo}

DELETE /site/organizations/:id/delete

GET /site/organizations/:id/members?offset=OFF&limit=LIM
  out: {“members”: Array[MaybeOrganizationMember]}

POST /site/organizations/:id/members/invite
  in: {“message”: Option[String],
    “invites”: Array[“id”: ExternalId[User] OR “email”: EmailAddress}
  out: {“result”: “success”,
    “invitees”: Array[“id”: ExternalId[User] OR “email”: EmailAddress]}

POST /site/organizations/:id/members/invites/cancel
  in: {“cancel”: Array[OrganizationMemberInvitation]}
  out: {“cancelled”: Array[OrganizationMemberInvitation]}

POST /site/organizations/:id/members/invites/accept?authToken=AUTH
  out: NoContent

POST /site/organizations/:id/members/invites/decline
  out: NoContent

POST /site/organizations/:id/members/invites/link
  out: {“link”: URL}

POST /site/organizations/:id/members/modify
  in:  {“members”: Array[{“userId”: ExternalId[User],
        “newRole”: OrganizationRole}]}
  out: {“modifications”: Array[{“userId”: ExternalId[User],
        “newRole”: OrganizationRole}]}

POST /site/organizations/:id/members/remove
  in:   {“members”: Array[{“userId”: ExternalId[User]}]}
  out:  {“removals”: Array[ExternalId[User]]}

GET /site/organizations/:id/libraries?offset=OFF&limit=LIM
  out:  {“libraries”: Array[LibraryInfo]}

GET /site/user-or-org/:handle
  out: {“type”: (“user” or “org”)
        “result”: (UserProfile or OrganizationView)}

GET /site/user-or-org/:handle/libraries
  out: {“libraries”: Array[LibraryInfo]}

Models:

  ExternalId[User] and PublicId[Organization] are both strings that uniquely identify users/orgs respectively. EmailAddress is a string that must look like an email address.

  OrganizationPermission is a string in the set
    “view_organization"
    "edit_organization"
    "invite_members"
    "modify_members"
    "remove_members"
    "add_libraries"
    "remove_libraries"

  OrganizationRole is a string in the set
    “member”
    “owner”

  BasicUser is an object
    id: ExternalId[User]
    firstName: String
    lastName: String
    pictureName: String
    username: String

  OrganizationMemberInvitation is an object:
    id: ExternalId[User] OR email: EmailAddress

  OrganizationInfo is an object
    id: PublicId[Organization]
    ownerId: ExternalId[User]
    handle: String
    name: String
    description: Option[String]
    publicUrl: Option[String]
    avatarPath: Option[String]
    members: Seq[BasicUser]
    numMembers: Int
    numLibraries: Int

  MembershipInfo is an object
    isInvited: Boolean
    permissions: Set[OrganizationPermission]
    role: Option[OrganizationRole]


  OrganizationCard is an object
    id: PublicId[Organization]
    ownerId: ExternalId[User]
    handle: String
    name: String
    description: Option[String]
    avatarPath: Option[String]
    numMembers: Int
    numLibraries: Int

  LibraryCardInfo
    id: PublicId[Library],
    name: String,
    description: Option[String],
    color: Option[LibraryColor], // system libraries have no color
    image: Option[LibraryImageInfo],
    slug: LibrarySlug,
    visibility: LibraryVisibility,
    owner: BasicUser,
    numKeeps: Int,
    numFollowers: Int,
    followers: Seq[BasicUser],
    numCollaborators: Int,
    collaborators: Seq[BasicUser],
    lastKept: DateTime,
    following: Option[Boolean], // @deprecated use membership object instead!
    listed: Option[Boolean] = None, // @deprecated use membership object instead! (should this library show up on owner's profile?)
    membership: Option[LibraryMembershipInfo],
    caption: Option[String] = None, // currently only for marketing page
    modifiedAt: DateTime,
    kind: LibraryKind,
    invite: Option[LibraryInviteInfo] = None) // currently only for Invited tab on viewer's own user profile


