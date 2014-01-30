'use strict';

angular.module('kifi.tags', ['util'])

.directive('kfTags', [
	'$timeout', '$location', 'util',
	function ($timeout, $location, util) {
		return {
			restrict: 'A',
			templateUrl: 'tags/tags.tpl.html',
			scope: {},
			link: function (scope, element /*, attrs*/ ) {
				scope.create = function (name) {
					console.log('create', name);
				};

				scope.rename = function (tag) {
					console.log('rename', tag);
				};

				scope.remove = function (tag) {
					console.log('remove', tag);
				};

				scope.clearFilter = function () {
					scope.filter.name = '';
					scope.focusFilter = true;
				};

				scope.showAddTag = function () {
					var name = scope.filter && scope.filter.name;
					if (name) {
						return !scope.tags.some(function (tag) {
							return tag.name.toLowerCase() === name.toLowerCase();
						});
					}
					return false;
				};

				scope.isActiveTag = function (tag) {
					return util.startsWith($location.path(), '/tag/' + tag.id);
				};

				var list = element.find('.kf-tag-list');
				console.log(list.position().top);
				list.css({
					position: 'absolute',
					top: list.position().top,
					bottom: 0
				});

				scope.$watch('filter.name', scope.refreshScroll);
				scope.$watch('tags', scope.refreshScroll);

				scope.tags = [{
					"id": "67846543-b5fc-45eb-b522-bfe3628544a3",
					"name": "Marketing",
					"keeps": 3
				}, {
					"id": "558eeb39-e83f-4783-8b47-cf5864de1e54",
					"name": "Local",
					"keeps": 0
				}, {
					"id": "20ed55ea-79da-46a5-8bee-3ce71963e6cc",
					"name": "Promise",
					"keeps": 3
				}, {
					"id": "495e62d9-496f-4e44-afda-d654db28019e",
					"name": "vim",
					"keeps": 8
				}, {
					"id": "3e72fae0-5a9b-40e6-92b3-f7c5bb722e1e",
					"name": "Font",
					"keeps": 4
				}, {
					"id": "cea802e5-2488-465c-8edd-517b2292082b",
					"name": "Food",
					"keeps": 1
				}, {
					"id": "8dd606a7-32c8-47f8-968e-d6666e95850b",
					"name": "Cool",
					"keeps": 1
				}, {
					"id": "8816ce05-1c42-4d2f-aaa9-44017fcb0a5f",
					"name": "Icons",
					"keeps": 2
				}, {
					"id": "400d45e5-344d-4b23-98fb-a595051ea1e8",
					"name": "Node.js",
					"keeps": 7
				}, {
					"id": "30611e1f-79a1-4e70-b682-d4772824aea9",
					"name": "Caltrain",
					"keeps": 1
				}, {
					"id": "a1ba084c-cae6-4f5f-9dbc-79a7204e67d8",
					"name": "Angular.js",
					"keeps": 8
				}, {
					"id": "37d35285-1744-4e14-b4a4-b470c1153703",
					"name": "Web Framework",
					"keeps": 10
				}, {
					"id": "fbe33902-da62-4640-a095-feefc188cfc6",
					"name": "Car",
					"keeps": 7
				}, {
					"id": "bed259dd-76ad-4322-884e-9e8ffb8d1139",
					"name": "Dev Tools",
					"keeps": 3
				}, {
					"id": "a7c03400-a2c0-40d4-b9eb-99896d55942d",
					"name": "Editors",
					"keeps": 1
				}, {
					"id": "c384732e-5534-4aee-b0ec-4af90097f791",
					"name": "Credit",
					"keeps": 5
				}, {
					"id": "555249b3-3413-48f4-b6c1-02c53b0f6a80",
					"name": "FICO",
					"keeps": 1
				}, {
					"id": "02fe5236-4d38-4aa6-aeec-9fd433b5ffe0",
					"name": "Networking",
					"keeps": 1
				}, {
					"id": "4e6fd02c-0f21-4f4f-a16c-14f701799c72",
					"name": "Startup",
					"keeps": 1
				}, {
					"id": "b3960cab-730a-48dc-8be3-c65c802790ca",
					"name": "Entrepreneur",
					"keeps": 1
				}, {
					"id": "5e836015-02d7-4777-956b-a7d18a04c334",
					"name": "Meetup",
					"keeps": 2
				}, {
					"id": "b4ec8d4f-ff2c-4817-ab50-e53cc6788f94",
					"name": "ECMAScript",
					"keeps": 1
				}, {
					"id": "769ca8c9-440b-4b14-a572-c484971c332a",
					"name": "JavaScript",
					"keeps": 36
				}, {
					"id": "00097af3-77fb-4a0f-9aa1-fabd4f8c3498",
					"name": "Utilities",
					"keeps": 1
				}, {
					"id": "453c4c56-8fbc-47cc-ac03-abc3df2c6678",
					"name": "IE",
					"keeps": 1
				}, {
					"id": "f8d09192-8df4-4dd9-824c-c3969c86970a",
					"name": "IntelliJ",
					"keeps": 1
				}, {
					"id": "2cf715dc-7d34-49a2-abd9-fc5d05bd41fe",
					"name": "Development",
					"keeps": 3
				}, {
					"id": "2747abe7-1892-4281-a3fd-736dc2f0ea4e",
					"name": "Setup",
					"keeps": 2
				}, {
					"id": "b2065590-5056-45a2-a097-fb9eb139ea65",
					"name": "Programming",
					"keeps": 5
				}, {
					"id": "611020a6-bb82-444e-b0f5-6ba3b50b7e14",
					"name": "UI",
					"keeps": 27
				}, {
					"id": "22abc317-5865-42a0-a3ee-6494f477493b",
					"name": "Color",
					"keeps": 3
				}, {
					"id": "880be119-ef9c-46e2-8fdd-d9a5a6625479",
					"name": "Design",
					"keeps": 11
				}, {
					"id": "dc6af2fc-d79c-4ee8-a286-b6ea24669b43",
					"name": "Flat Design",
					"keeps": 3
				}, {
					"id": "ee46cbb6-f920-4ade-82e5-4e5724b6b2af",
					"name": "Go",
					"keeps": 1
				}, {
					"id": "ccdb4f56-70c6-46e2-9146-f0717dd745aa",
					"name": "Language",
					"keeps": 1
				}, {
					"id": "cefeedd4-fee4-4bfd-a676-1d39d8b7bfd2",
					"name": "Driver License",
					"keeps": 1
				}, {
					"id": "14fc8fe7-cd78-4867-9cba-3a154fd9bbab",
					"name": "Contacts",
					"keeps": 1
				}, {
					"id": "996dde84-8528-4717-b371-dcae86d494ca",
					"name": "42",
					"keeps": 12
				}, {
					"id": "90d65d4b-10f0-40e6-9aaf-46ff7ea518f4",
					"name": "Poker",
					"keeps": 1
				}, {
					"id": "cbf4567a-0ebf-4a37-ba98-7a82b327278c",
					"name": "Video",
					"keeps": 1
				}, {
					"id": "91be5405-6647-4d07-af90-9e47f4467ad2",
					"name": "YouTube",
					"keeps": 1
				}, {
					"id": "ff47fba3-445b-49f6-ac75-16d4503d4a92",
					"name": "Me",
					"keeps": 1
				}, {
					"id": "a97c25ff-593d-462f-bc83-24234fd9f2aa",
					"name": "Git",
					"keeps": 2
				}, {
					"id": "6245b942-9782-4543-a738-dfd2dc65160d",
					"name": "Finance",
					"keeps": 8
				}, {
					"id": "6b56fba7-3442-43cf-85f8-242db8ab81b6",
					"name": "Software",
					"keeps": 1
				}, {
					"id": "532f6985-301b-4951-9d35-f5825b53b126",
					"name": "Mail",
					"keeps": 0
				}, {
					"id": "9ade19ea-39e1-4490-9ae0-c5f3996859a0",
					"name": "Dev",
					"keeps": 8
				}, {
					"id": "656af10c-adda-4c24-a4bf-634be61bdb65",
					"name": "Password",
					"keeps": 1
				}, {
					"id": "e3a2897a-7eaa-48b3-a72e-f2debb7a8e94",
					"name": "Mac",
					"keeps": 3
				}, {
					"id": "5fdc6765-0f5b-4cb2-b64c-912d999d380e",
					"name": "Open Source Library",
					"keeps": 21
				}, {
					"id": "08e20179-f867-4c05-9379-b79af190c640",
					"name": "HTML5",
					"keeps": 1
				}, {
					"id": "879d7e5a-700b-4bac-b7f5-6a2672fac98e",
					"name": "Smart",
					"keeps": 1
				}, {
					"id": "e8d322de-15d0-4fb6-8c66-2220f789f3b3",
					"name": "Game",
					"keeps": 1
				}, {
					"id": "c1c5a999-016e-4db0-8b07-0f289d3c9add",
					"name": "Gadget",
					"keeps": 1
				}, {
					"id": "08928aa0-aa8b-4345-826c-e05c25da86a5",
					"name": "GitHub",
					"keeps": 24
				}, {
					"id": "a477b5b7-6055-4907-a830-c74a47b39aec",
					"name": "Proof Reading",
					"keeps": 1
				}, {
					"id": "08ada053-a7b1-4410-8652-3a74158c90a6",
					"name": "CSS",
					"keeps": 3
				}, {
					"id": "d09d5b79-f792-4c5e-ac8a-4819fd2c98b9",
					"name": "kifi",
					"keeps": 3
				}, {
					"id": "4c7d9032-afe9-47b9-95e4-d5de7c1f09cb",
					"name": "Entertainments",
					"keeps": 2
				}, {
					"id": "b60b1a0c-c0a3-4950-9083-a0e7244a4c8c",
					"name": "Chat",
					"keeps": 7
				}, {
					"id": "6b86bae8-964f-42e6-a50c-f6f1f949f9d8",
					"name": "Resources",
					"keeps": 1
				}, {
					"id": "a5ef0277-05ec-4994-9d6d-9b685fcea05b",
					"name": "Todo",
					"keeps": 1
				}, {
					"id": "f0aa8386-fe26-45e8-8411-797b95e1d0c9",
					"name": "iCloud",
					"keeps": 1
				}, {
					"id": "aac9935a-3e0f-44f4-8391-706dec3d4a10",
					"name": "Bank",
					"keeps": 3
				}, {
					"id": "0860b244-24ca-4e84-8316-06b12c68b10a",
					"name": "Library",
					"keeps": 4
				}, {
					"id": "830efc51-85b6-47fb-85b5-d6a9193ef173",
					"name": "Resume",
					"keeps": 1
				}, {
					"id": "a21f89b4-d3f6-4fca-a461-67a9e3591601",
					"name": "SEO",
					"keeps": 1
				}, {
					"id": "52df7426-8775-4dc5-8151-fc665f028e69",
					"name": "IRC",
					"keeps": 4
				}, {
					"id": "2bd82eb3-e099-4b8d-b9d3-18b4b19ca69a",
					"name": "Ratings",
					"keeps": 1
				}, {
					"id": "03478dec-10cc-49a4-baf3-99ac9ed139aa",
					"name": "Compare",
					"keeps": 1
				}, {
					"id": "433c755d-c362-4d47-9ed4-86eccfc41436",
					"name": "Research",
					"keeps": 1
				}, {
					"id": "48a861b8-e0e2-4864-86da-cfde1c827b9a",
					"name": "Headphones",
					"keeps": 2
				}, {
					"id": "f05c3f00-9459-4bba-b891-674b8c55ba6d",
					"name": "Performance",
					"keeps": 2
				}, {
					"id": "10fecccf-b24c-498f-9316-f2a0efdd1377",
					"name": "VISA",
					"keeps": 3
				}, {
					"id": "f640524b-90a7-4895-8c39-ebf7be5287b9",
					"name": "CMU",
					"keeps": 2
				}, {
					"id": "a7fb5cf8-2631-4407-9fd1-b06ae1a9056f",
					"name": "Shopping",
					"keeps": 2
				}, {
					"id": "b04eb620-f14c-4dd2-927f-a340259d8565",
					"name": "Web",
					"keeps": 14
				}, {
					"id": "35ff3a59-bdc6-4ecd-8f7a-f68780623780",
					"name": "News",
					"keeps": 6
				}, {
					"id": "94159cae-d01b-47e0-80bd-b11cc607f7a9",
					"name": "Productivity",
					"keeps": 1
				}, {
					"id": "a1c5f6b8-3b33-4b58-8421-a07ca12c6ec2",
					"name": "Cars",
					"keeps": 1
				}, {
					"id": "f264379b-089b-4b64-9f06-a778d8aded64",
					"name": "Scala",
					"keeps": 5
				}, {
					"id": "424cfe30-bb77-4283-8b0b-77776359d034",
					"name": "Social",
					"keeps": 16
				}];
			}
		};
	}
]);
