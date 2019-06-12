def shellLib() {
	return '''
function multail() {
  local ppid
  local pids
  local basename
  local pidfile
  local sedpid
  local tailpid
  pids=
  ppid="$1"
  shift
  while [ "$1" != "" ] ; do
    basename=`basename "$1"`
    pidfile=`mktemp`
    ( tail -F "$1" --pid="$ppid" & echo $! 1>&3 ) 3> "$pidfile" | sed -u "s/^/$basename: /" >&2 &
    sedpid=$!
    tailpid=$(<"$pidfile")
    rm -f "$pidfile"
    pids="$tailpid $sedpid $pids"
    shift
  done
  echo "$pids"
}

function suspendshellverbose() {
    local oldopts
    local retval
    oldopts=$( echo $- | grep x )
    set +x
    retval=0
    "$@" || retval=$?
    if [ -n "$oldopts" ]; then set -x ; else set +x ; fi
    return $retval
}

function mockfedorarpms() {
# build $3- for fedora release number $1 and deposit in $2
  local tailpids
  local relpath
  local retval
  local release
  local definebuildnumber

  definebuildnumber='no_build_number 1'
  if [ "$1" == "--define_build_number" ]
  then
     test -n "$BUILD_NUMBER"
     definebuildnumber="build_number $BUILD_NUMBER"
     shift
  fi

# NO: groups | grep -q ^mock || exec sg mock -c "$SHELL -xe $0"

  relpath=`python -c 'import os, sys ; print os.path.relpath(sys.argv[1])' "$2"`

  release="$1"
  shift
  shift

  test -x /usr/local/bin/mocklock && m=/usr/local/bin/mocklock || m=./mocklock
  $m -r fedora-"$release"-x86_64-generic \
    --no-clean --no-cleanup-after --unpriv \
    --define "$definebuildnumber" \
    --resultdir=./"$relpath"/ --rebuild "$@" &
  pid=$!

  tailpids=`multail "$pid" "$relpath"/build.log`

  wait "$pid" || retval=$?

  for tailpid in $tailpids ; do
      while kill -0 $tailpid >/dev/null 2>&1 ; do
          sleep 0.1
      done
  done

  return $retval
}

function automockfedorarpms() {
  for file in src/*.src.rpm ; do
    test -f "$file" || { echo "$file is not a source RPM" >&2 ; return 19 ; }
  done
  mkdir -p out/$2
  suspendshellverbose mockfedorarpms $1 $2 out/$2/ src/*.src.rpm
  # mv -f src/*.src.rpm out/
}

function autouploadrpms() {
  /var/lib/jenkins/userContent/upload-deliverables out/*/*.rpm
}
'''
}

def listify(aShellString) {
  result = aShellString.readLines()
  if (result.size() == 1 && result[0] == "") {
    result = []
  }
  return result
}

def autolistrpms() {
  return listify(sh(
    script: "ls -1 out/*/*.rpm 2>/dev/null || true",
    returnStdout: true
  ).trim())
}

def automockfedorarpms(myRelease) {
	sh("set -e\n" + shellLib() + "\nautomockfedorarpms --define_build_number ${myRelease}")
}

def automockfedorarpms_all(releases) {
	funcs.combo({
		def myRelease = it[0]
			return {
				stage("RPMs for Fedora ${myRelease}") {
					script {
						automockfedorarpms(myRelease)
					}
				}
			}
		}, [
			releases,
		])
}

def autouploadfedorarpms(myRelease) {
	sh("set -e\n" + shellLib() + "\nautouploadrpms")
}

def call(checkout_step = null, srpm_step = null, srpm_deps = null) {
	def RELEASE = funcs.loadParameter('parameters.groovy', 'RELEASE', '28')

	pipeline {

		agent { label 'master' }

		triggers {
			pollSCM('H H * * *')
		}

		options {
			disableConcurrentBuilds()
			skipDefaultCheckout()
		}

		parameters {
			string defaultValue: '', description: "Override which Fedora releases to build for.  If empty, defaults to ${RELEASE}.", name: 'RELEASE', trim: true
		}

		stages {
			stage('Begin') {
				steps {
					script {
						funcs.announceBeginning()
						funcs.durable()
					}
				}
			}
			stage('Checkout') {
				steps {
					dir('src') {
						checkout([
							$class: 'GitSCM',
							branches: scm.branches,
							extensions: [
								[$class: 'CleanCheckout'],
								[
									$class: 'SubmoduleOption',
									disableSubmodules: false,
									parentCredentials: false,
									recursiveSubmodules: true,
									trackingSubmodules: false
								],
							],
							userRemoteConfigs: scm.userRemoteConfigs
						])
					}
					script {
						if (checkout_step != null) {
							checkout_step()
						}
					}
					sh 'cp -a /usr/local/bin/mocklock .'
					stash includes: '**', name: 'source', useDefaultExcludes: false
				}
			}
			stage('Dispatch') {
				agent { label 'mock' }
				stages {
					stage('Deps') {
						steps {
							script {
								funcs.dnfInstall([
									'rpm-build',
									'pypipackage-to-srpm',
									'shyaml',
									'python2-nose',
									'python3-nose',
									'python2',
									'python3',
									'python2-setuptools',
									'python3-setuptools',
									'python3-setuptools_scm',
									'python3-setuptools_scm_git_archive',
									'python2-pyyaml',
									'python3-PyYAML',
								])
								if (srpm_deps != null) {
									funcs.dnfInstall(srpm_deps)
								}
							}
						}
					}
					stage('Unstash') {
						steps {
							deleteDir()
							unstash 'source'
						}
					}
					stage('Test') {
						steps {
							script {
								try {
									dir('src') {
										sh '''
										set -e
										if test -f setup.py ; then
											relnum=$(rpm -q fedora-release --queryformat '%{version}')
											if head -1 setup.py | grep -q python3 ; then
												python=nosetests-3
											elif head -1 setup.py | grep -q python2 ; then
												python=nosetests-2
											elif [ "$relnum" > 28 ] ; then
												python=nosetests-3
											else
												python=nosetests-2
											fi
											if [ $(find . -name '*_test.py' -o -name 'test_*.py' | wc -l) != 0 ] ; then
												$python -v --with-xunit --xunit-file=../xunit.xml
											fi
										fi
										'''
									}
								} finally {
									if (fileExists("xunit.xml")) {
										stash includes: 'xunit.xml', name: 'xunit'
									}
								}
							}
						}
					}
					stage('SRPM') {
						steps {
							script {
								if (srpm_step != null) {
									srpm_step()
								} else {
									dir('src') {
										if (fileExists('setup.py')) {
											sh '''
												set -e
												rm -rf build dist
												relnum=$(rpm -q fedora-release --queryformat '%{version}')
												if head -1 setup.py | grep -q python3 ; then
													python=python3
												elif head -1 setup.py | grep -q python2 ; then
													python=python2
												elif [ "$relnum" > 28 ] ; then
													python=python3
												else
													python=python2
												fi
												$python setup.py sdist
												$python setup.py bdist_rpm --spec-only
												rpmbuild --define "_srcrpmdir ./" --define "_sourcedir dist/" -bs dist/*.spec
												rm -rf build dist
											'''
										} else if (fileExists('pypipackage-to-srpm.yaml')) {
											script {
												def url = sh('shyaml get-value url < pypipackage-to-srpm.yaml', returnStdout: true).trim()
												def sum = sh('shyaml get-value sha256sum < pypipackage-to-srpm.yaml', returnStdout: true).trim()
												basename = funcs.downloadUrl(url, null, sum, ".")
												sh """
												y=pypipackage-to-srpm.yaml
												mangle_name=
												if [ "\$(shyaml get-value mangle_name True < \$y)" == "False" ] ; then
													mangle_name=--no-mangle-name
												fi
												epoch=$(shyaml get-value epoch '' < \$y || true)
												if [ "\$epoch" != "" ] ; then
													epoch="--epoch=\$epoch"
												fi
												python_versions=\$(shyaml get-values python_versions < \$y || true)
												if [ "\$python_versions" == "" ] ; then
													python_versions="2 3"
												fi
												if [ "\$python_versions" == "2 3" -o "\$python_versions" == "3 2" ] ; then
													if [ "\$mangle_name" == "--no-mangle-name" ] ; then
														>&2 echo error: cannot build for two Python versions without mangling the name of the package
														exit 36
													fi
												fi
												diffs=1
												for f in *.diff ; do
													test -f "\$f" || diffs=0
												done
												for v in \$python_versions ; do
													if [ "\$diffs" == "1" ] ; then
														python"\$v" `which pypipackage-to-srpm` --no-binary-rpms \$epoch \$mangle_name "${basename}" *.diff
													else
														python"\$v" `which pypipackage-to-srpm` --no-binary-rpms \$epoch \$mangle_name "${basename}"
													fi
												done
												"""
											}
										} else {
											sh 'make srpm'
										}
									}
								}
							}
						}
					}
					stage('RPMs') {
						steps {
							dir('out') {
								deleteDir()
							}
							script {
								if (params.RELEASE != '') {
									RELEASE = params.RELEASE
								}
								println "Building RPMs for Fedora releases ${RELEASE}"
								parallel automockfedorarpms_all(RELEASE.split(' '))
							}
						}
					}
					stage('Stash') {
						steps {
							stash includes: 'out/*/*.rpm', name: 'out'
						}
					}
				}
			}
			stage('Unstash') {
				steps {
					dir("out") {
						deleteDir()
					}
					unstash 'out'
				}
			}
			stage('Publish') {
				when {
					expression {
						return env.BRANCH_NAME == "master"
					}
				}
				steps {
					script {
                                                def outputs = autolistrpms()
                                                println outputs
                                                outputs = outputs.collect{ funcs.wrapLi(funcs.escapeXml(it)) }.join("\n")
						autouploadfedorarpms()
						currentBuild.description = "<p>Outputs:</p>" + funcs.wrapUl(outputs)
					}
				}
			}
		}
		post {
			always {
				node('master') {
					script {
						sh 'rm -f xunit.xml'
						try {
							unstash 'xunit'
							junit 'xunit.xml'
						} catch (Exception e) {
							println "Cannot unstash xunit results, assuming none."
							println e
							sh 'ls -la'
						}
						funcs.announceEnd(currentBuild.currentResult)
					}
				}
			}
		}
	}
}
