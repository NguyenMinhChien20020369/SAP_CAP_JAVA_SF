sap.ui.define([
    "sap/fe/test/JourneyRunner",
	"fioriproject/test/integration/pages/ProjectList",
	"fioriproject/test/integration/pages/ProjectObjectPage",
	"fioriproject/test/integration/pages/MemberObjectPage"
], function (JourneyRunner, ProjectList, ProjectObjectPage, MemberObjectPage) {
    'use strict';

    var runner = new JourneyRunner({
        launchUrl: sap.ui.require.toUrl('fioriproject') + '/test/flpSandbox.html#fioriproject-tile',
        pages: {
			onTheProjectList: ProjectList,
			onTheProjectObjectPage: ProjectObjectPage,
			onTheMemberObjectPage: MemberObjectPage
        },
        async: true
    });

    return runner;
});

