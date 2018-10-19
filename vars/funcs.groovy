def combo(task, axes) {
    def tasks = [:]
    def comboEntry = []
    def comboBuilder
    comboBuilder = {
        def a, int level -> for ( entry in a[0] ) {
            comboEntry[level] = entry
            if (a.size() > 1) {
                comboBuilder(a.drop(1), level + 1)
            }
            else {
                tasks[comboEntry.join("_")] = task(comboEntry.collect())
            }
        }
    }
    comboBuilder(axes, 0)
    tasks.sort { it.key }
    return tasks
}

def durable() {
    System.setProperty("org.jenkinsci.plugins.durabletask.BourneShellScript.HEARTBEAT_CHECK_INTERVAL", "3600")
}

def dnfInstall(deps) {
  sh """#!/bin/bash -xe
     (
         flock 9
         deps="${deps.join(' ')}"
         rpm -q \$deps || sudo dnf install --disablerepo='*qubes*' -y \$deps
     ) 9> /tmp/\$USER-dnf-lock
     """
}

def aptInstall(deps) {
  sh """#!/bin/bash -xe
     (
         flock 9
         deps="${deps.join(' ')}"
         dpkg-query -s \$deps || { sudo apt-get -q update && sudo apt-get -y install \$deps ; }
     ) 9> /tmp/\$USER-apt-lock
     """
}

def aptEnableSrc() {
  sh '''#!/bin/bash -xe
     (
         flock 9
         changed=0
         tmp=$(mktemp)
         cat /etc/apt/sources.list > "$tmp"
         sed -i 's/#deb-src/deb-src/' "$tmp"
         cmp /etc/apt/sources.list "$tmp" || {
           sudo tee /etc/apt/sources.list < "$tmp"
           changed=1
         }
         cat /etc/apt/sources.list > "$tmp"
         sed -E -i 's|debian main/([a-z]+) main|debian \\1 main|' "$tmp"
         cmp /etc/apt/sources.list "$tmp" || {
           sudo tee /etc/apt/sources.list < "$tmp"
           changed=1
         }
         if [ $changed = 1 ] ; then sudo apt-get -q update ; fi
     ) 9> /tmp/\$USER-apt-lock
     '''
}

def announceBeginning() {
    sh """
       test -x /usr/local/bin/announce-build-result || exit
       /usr/local/bin/announce-build-result has begun
       """
}

def announceEnd(status) {
    sh """
       test -x /usr/local/bin/announce-build-result || exit
       /usr/local/bin/announce-build-result finished with status ${status}
       """
}

def uploadDeliverables(spec) {
    sh """
       test -x /usr/local/bin/upload-deliverables || exit
       /usr/local/bin/upload-deliverables ${spec}
       """
}

def describeCause(currentBuild) {
	def causes = currentBuild.rawBuild.getCauses()
	def manualCause = currentBuild.rawBuild.getCause(hudson.model.Cause$UserIdCause)
	def scmCause = currentBuild.rawBuild.getCause(hudson.triggers.SCMTrigger$SCMTriggerCause)
	def upstreamCause = currentBuild.rawBuild.getCause(hudson.model.Cause$UpstreamCause)
	def buildTrigger = "Triggered by ${causes}"
	if (upstreamCause != null) {
		buildTrigger = "Triggered by upstream job " + upstreamCause.upstreamProject
	} else if (manualCause != null) {
		buildTrigger = "${manualCause.shortDescription}"
	} else if (scmCause != null) {
		buildTrigger = "${scmCause.shortDescription}"
	}
	return buildTrigger
}

def isUpstreamCause(currentBuild) {
	def upstreamCause = currentBuild.rawBuild.getCause(hudson.model.Cause$UpstreamCause)
	return upstreamCause != null
}

def getUpstreamProject(currentBuild) {
	if (isUpstreamCause(currentBuild)) {
		return currentBuild.rawBuild.getCause(hudson.model.Cause$UpstreamCause).upstreamProject
	}
	return null
}

def loadParameter(filename, name, defaultValue) {
    GroovyShell shell = new GroovyShell()
    defaultsScript = [:]
    try {
        defaultsScript = shell.parse(new File(env.JENKINS_HOME + "/jobs/" + env.JOB_NAME + "/" + "parameters.groovy")).run()
    }
    catch(IOException ex){
        try {
            defaultsScript = shell.parse(new File(env.JENKINS_HOME + "/jobs/" + env.JOB_NAME.split("/")[0] + "/" + "parameters.groovy")).run()
        }
        catch(IOException ex2) {
	    defaultsScript = [:]
        }
    }
    x = defaultsScript.find{ it.key == name }?.value
    if (x) {
        return x
    }
    return defaultValue
}
