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
                tasks[comboEntry.join(" ")] = task(comboEntry.collect())
            }
        }
    }
    comboBuilder(axes, 0)
    tasks.sort { it.key }
    return tasks
}

def toInt(aNumber) {
    return aNumber.toInteger().intValue()
}

def durable() {
    System.setProperty("org.jenkinsci.plugins.durabletask.BourneShellScript.HEARTBEAT_CHECK_INTERVAL", "3600")
}

String getrpmfield(String filename, String field) {
	String str = sh(
		returnStdout: true,
		script: """#!/bin/bash
			rpmspec -P ${filename} | grep ^${field}: | awk ' { print \$2 } ' | head -1
		"""
	).trim()
	return str
}

def getrpmfieldlist(String filename, String fieldPrefix) {
	ret = []
	for (i in [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10]) {
		s = getrpmfield(filename, "${fieldPrefix}${i}")
		if (s == "") {
			break
		}
		ret.add(s)
	}
	return ret
}


def getrpmsources(String filename) {
	return getrpmfieldlist(filename, "Source")
}

def getrpmpatches(String filename) {
	return getrpmfieldlist(filename, "Patch")
}

def dnfInstall(deps) {
  sh """#!/bin/bash -xe
     (
         flock 9
         deps="${deps.join(' ')}"
         rpm -q \$deps || sudo dnf install --disablerepo='*qubes*' --disableplugin='*qubes*' -y \$deps
     ) 9> /tmp/\$USER-dnf-lock
     """
}

def aptInstall(deps) {
  sh """#!/bin/bash -e
     (
         flock 9
         deps="${deps.join(' ')}"
         dpkg-query -s \$deps >/dev/null || { sudo apt-get -q update && sudo apt-get -y install \$deps ; }
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
    sh '''
       test -x /usr/local/bin/announce-build-result && f=/usr/local/bin/announce-build-result || test -f /var/lib/jenkins/userContent/announce-build-result && f=/var/lib/jenkins/userContent/announce-build-result || exit 0
       $f has begun
       '''
}

def announceEnd(status) {
    sh '''
       test -x /usr/local/bin/announce-build-result && f=/usr/local/bin/announce-build-result || test -f /var/lib/jenkins/userContent/announce-build-result && f=/var/lib/jenkins/userContent/announce-build-result || exit 0
       $f finished with status ${status}
       '''
}

def uploadDeliverables(spec) {
    sh '''
       test -x /usr/local/bin/announce-build-result && f=/usr/local/bin/announce-build-result || test -f /var/lib/jenkins/userContent/announce-build-result && f=/var/lib/jenkins/userContent/announce-build-result || exit 0
       $f ${spec}
       '''
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

def srpmFromSpecWithUrl(filename, srcdir, outdir, sha256sum='') {
	// Specfile SOURCE0 has the URL.  Sha256sum validates the URL's ontents.
	// srcdir is where the URL file is deposited.
	// outdir is where the source RPM is deposited.  It is customarily src/ cos that's where automockfedorarpms finds it.
	return {
		url = getrpmfield(filename, "Source0")
		println "URL of source is ${url} -- downloading now."
		sh "wget -c --progress=dot:giga --timeout=15 -O ${srcdir}/\$(basename ${url}) ${url}"
		if (sha256sum != '') {
			sum = sh(
				returnStdout: true,
				script: "sha256sum ${srcdir}/\$(basename ${url})"
			).tokenize(" ")[0]
			assert sum == sha256sum: "SHA256 sum of downloaded file ${sum} does not match ${sha256sum}"
		}
		sh "rpmbuild --define \"_srcrpmdir ${outdir}\" --define \"_sourcedir ${srcdir}\" -bs ${filename}"
	}
}

// Create source RPM from a source tree.  Finds first specfile in src/ and uses that.
def srpmFromSpecAndSourceTree(srcdir, outdir) {
	// srcdir is the directory tree that contains the source files to be tarred up.
	// outdir is where the source RPM is deposited.  It is customarily src/ cos that's where automockfedorarpms finds it.
	return {
		println "Retrieving specfiles..."
		filename = sh(
			returnStdout: true,
			script: "set -o pipefail ; ls -1 src/*.spec | head -1"
		).trim()
		println "Filename of specfile is ${filename}."
		tarball = getrpmfield(filename, "Source0")
		println "Filename of source tarball is ${tarball}."
		// This makes the tarball.
		sh "p=\$PWD && cd ${srcdir} && cd .. && bn=\$(basename ${srcdir}) && tar cvzf ${tarball} \$bn"
		// The following code copies up to ten source files as specified by the
		// specfile, if they exist in the src/ directory where the specfile is.
		for (i in getrpmsources(filename)) {
			sh "if test -f src/${i} ; then cp src/${i} ${srcdir}/.. ; fi"
		}
		for (i in getrpmpatches(filename)) {
			sh "if test -f src/${i} ; then cp src/${i} ${srcdir}/.. ; fi"
		}
		// This makes the source RPM.
		sh "rpmbuild --define \"_srcrpmdir ${outdir}\" --define \"_sourcedir ${srcdir}/..\" -bs ${filename}"
	}
}

def checkoutRepoAtCommit(repo, commit, outdir) {
	// outdir is the directory where the repo will be checked out.
	return {
		dir(outdir) {
			checkout(
				[
					$class: 'GitSCM',
					branches: [[name: commit]],
					doGenerateSubmoduleConfigurations: true,
					extensions: [],
					submoduleCfg: [],
					userRemoteConfigs: [[url: repo]]
				]
			)
			sh 'git clean -fxd'
		}
	}
}

def downloadUrl(url, filename, sha256sum, outdir) {
	// outdir is the directory where the file will appear.
	return {
		dir(outdir) {
			sh """
                                set -x
                                set +e
				s=\$(sha256sum ${filename} | cut -f 1 -d ' ' || true)
				if [ "\$s" != "${sha256sum}" ] ; then
                                        rm -f -- ${filename}
                                        wget -O ${filename} -- ${url} || exit \$?
                                        s=\$(sha256sum ${filename} | cut -f 1 -d ' ' || true)
                                        if [ "\$s" != "${sha256sum}" ] ; then exit 8 ; fi
				fi
			"""
		}
	}
}
