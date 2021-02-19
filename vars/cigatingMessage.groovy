import groovy.json.JsonOutput

/**
 * Method that uses contra-lib shared library
 * to create the VirtualTopic.eng.ci.brew-build.test.messageType messages
 * @param messageType: queued, running, complete, error
 * @param parsedMessage: the parsed CI Message
 * @param component: name of RPM package
 * @param result: one of https://pagure.io/fedora-ci/messages/blob/master/f/schemas/test-complete.yaml#_14
 * @return
 * all credit goes to jbieren
 */

def call(Map parameters = [:]){
    def messageType = parameters.get("messageType", "")
    def parsedMsg = parameters.get("parsedMessage", "")
    def component = parameters.get("component", "").toString()
    def myresult = parameters.get("result", "").toString()

    mymessage = readJSON text: parsedMsg
    println("MY PK MESSAGE ${mymessage}")
    println("MY PK MESSAGE type ${messageType}")
    println("MY PK MESSAGE component ${component}")
    println("MY PK MESSAGE result ${myresult}")
    // brew.build.complete
    // mytaskid = mymessage.info.task_id
    // issuer = mymessage.info.owner_name
    // mysource = mymessage.info.source
    // mynvr = mymessage.info.nvr

    // brew.build.tag
    mytaskid = mymessage.build.task_id
    issuer = mymessage.build.owner_name
    mysource = mymessage.build.source
    mynvr = mymessage.build.nvr

    mytype = 'brew-build'
    // mynamespace = 'insights-client.brew-build'
    mynamespace = component + ".brew-build"
    println("DEBUG: mymespace is: ${mynamespace}")

    pipelineID = "platform-data-qe-" + component + "-brew-build-" + mytaskid
    println("DEBUG: pipelineID is: ${pipelineID}")

    // Set values that go in multiple closures
    myTopic = "VirtualTopic.eng.ci.brew-build.test." + messageType
    println("DEBUG: myTopic is: ${myTopic}")

    // Create common message body content
    // myContactContent = msgBusContactContent(name: "insights-client", team: "Insights Client QE", docs: '', email: "pakotvan@redhat.com")
    myContactContent = msgBusContactContent(name: component, team: "Insights Client QE", docs: '', email: "pakotvan@redhat.com")
    println("DEBUG: myContactContent is:" + myContactContent())

    // myArtifactContent = msgBusArtifactContent(type: 'brew-build', id: "${parsedMsg['build']['task_id']}", component: 'insights-client', issuer: "${parsedMsg['build']['owner_name']}", nvr: env.nvr, scratch: false, source: env.RPM_REQUEST_SOURCE ?: "UNKNOWN")
    // myArtifactContent = msgBusArtifactContent(type: mytype, id: id, component: component, issuer: issuer, nvr: env.nvr, scratch: false, source: env.RPM_REQUEST_SOURCE ?: "UNKNOWN")
    myArtifactContent = msgBusArtifactContent(type: mytype, id: mytaskid, component: component, issuer: issuer, nvr: mynvr, scratch: false, source: mysource ?: "UNKNOWN")
    println("DEBUG: myArtifactContent is:" + myArtifactContent())

    // name: TODO_you dont set env.currentStage. You only send messages from the executeTests function. What stage calls that function? You should be able to just hardcode that in here
    myStageContent = msgBusStageContent(name: 'run_tests')
    println("DEBUG: myStageContent is:" + myStageContent())

    myPipelineContent = msgBusPipelineContent(name: "ci-gating", stage: myStageContent(), id: pipelineID.toString())
    println("DEBUG: myPipelineContent is:" + myPipelineContent())

    // category: pick one from https://pagure.io/fedora-ci/messages/blob/master/f/schemas/test-common.yaml#_12
    myTestContent = msgBusTestContent(note: component + "-tests", category: 'functional', namespace: mynamespace, type: 'tier0', docs: '', result: myresult )
    println("DEBUG: myTestContent is:" + myTestContent())

    // Create type specific content and construct message
    switch (messageType.toString()) {
        // Queued and running messages have the same spec
        case ['queued', 'running']:
            println("inside switch statement")
            myConstructedMessage = msgBusTestQueued(contact: myContactContent(), artifact: myArtifactContent(), pipeline: myPipelineContent(), test: myTestContent())
            println("DEBUG: myConstructedMessage is:" + myConstructedMessage())
            break
        case 'complete':
            // os: OS your jenkins master running - RHEL-7, RHEL-8
            // provider: upshift? RHOS? Where is hosting your jenkins master
            // variant: What variant of rhel is your master running? `cat /etc/os-release`
            // mySystemContent = msgBusSystemContent(label: "insights-client", os: "RHEL-8", provider: "RHOS", architecture: "x86_64", variant: "Server")
            mySystemContent = msgBusSystemContent(label: component, os: "RHEL-8", provider: "RHOS", architecture: "x86_64", variant: "Server")
            println("DEBUG: mySystemContent is:" + mySystemContent())

            myConstructedMessage = msgBusTestComplete(contact: myContactContent(), artifact: myArtifactContent(), pipeline: myPipelineContent(), system: [mySystemContent()], test: myTestContent())
            println("DEBUG: myConstructedMessage is:" + myConstructedMessage())
            break
        case 'error':
            myConstructedMessage = msgBusTestError(contact: myContactContent(), artifact: myArtifactContent(), pipeline: myPipelineContent(), test: myTestContent())
            println("DEBUG: myConstructedMessage is:" + myConstructedMessage())
            break
    }

    return [ 'topic': myTopic, 'properties': '', 'content': myConstructedMessage() ]
}
