def call() {
	def buildName = sh(
		script: '''#!/bin/sh
			for a in src upstream upstream/*
			do
				if test -d "$a" && test -d "$a"/.git
				then
					pushd "$a" >/dev/null
					echo -n "$a " ; git log --oneline -n 1
					popd >/dev/null
				fi
			done
		''',
		returnStdout: true
	).trim().split("\n")
	descs = []
	dns = []
	for (String desc : buildName) {
		x = desc.split(" ", 3)
		dns.push(x[1])
		x[0] = funcs.wrapKbd(funcs.escapeXml(x[0])) + ": "
		x[1] = funcs.wrapKbd(funcs.escapeXml(x[1]))
		x[2] = funcs.escapeXml(x[2])
		x = funcs.wrapLi(x.join("\n"))
		descs.push(x)
	}
	if (dns.size() > 0) {
		currentBuild.displayName = "#" + env.BUILD_NUMBER + ": " + dns.join(" / ")
	}
	if (descs.size() > 0) {
		desc = funcs.wrapUl(descs.join("\n"))
		currentBuild.description = ((currentBuild.description == null) ? "" : currentBuild.description) + "<p>Source tracking:</p>\n" + desc
	}
}
