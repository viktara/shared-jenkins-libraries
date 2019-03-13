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

  tailpids=`multail "$pid" "$relpath"/build.log "$relpath"/root.log "$relpath"/state.log "$relpath"/hw_info.log "$relpath"/installed_pkgs.log`

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
  /usr/local/bin/upload-deliverables out/*/*.rpm
}
'''
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

def call(checkout_step = null, srpm_step = null) {
	def RELEASE = funcs.loadParameter('parameters.groovy', 'RELEASE', '28')

	pipeline {

		agent { label 'master' }

		triggers {
			pollSCM('* * * * *')
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
					sh 'rm -f xunit.xml'
					dir('src') {
						checkout scm
						sh 'git clean -fxd'
					}
					script {
						if (checkout_step != null) {
							checkout_step()
						}
					}
				}
			}
			stage('Test') {
				steps {
					dir('src') {
						sh '''
							set -e
							if test -f setup.py ; then
								if [ $(find . -name '*_test.py' -o -name 'test_*.py' | wc -l) != 0 ] ; then
									nosetests -v --with-xunit --xunit-file=../xunit.xml
								fi
							fi
						'''
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
										if head -1 setup.py | grep -q python3 ; then
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
									sh '''
										y=pypipackage-to-srpm.yaml
										url=$(shyaml get-value url < $y)
										fn=$(basename "$url")
										sha256sum=$(shyaml get-value sha256sum < $y)
										mangle_name=
										if [ "$(shyaml get-value mangle_name True < $y)" == "False" ] ; then
											mangle_name=--no-mangle-name
										fi
										epoch=$(shyaml get-value epoch < $y || true)
										if [ "$epoch" != "" ] ; then
											epoch="--epoch=$epoch"
										fi
										python_versions=$(shyaml get-values python_versions < $y || true)
										if [ "$python_versions" == "" ] ; then
											python_versions="2 3"
										fi
										if [ "$python_versions" == "2 3" -o "$python_versions" == "3 2" ] ; then
											if [ "$mangle_name" == "--no-mangle-name" ] ; then
												>&2 echo error: cannot build for two Python versions without mangling the name of the package
												exit 36
											fi
										fi
										diffs=1
										for f in *.diff ; do
											test -f "$f" || diffs=0
										done
										wget --progress=dot:giga --timeout=15 -O "$fn" "$url"
										actualsum=$(sha256sum "$fn" | cut -d ' ' -f 1)
										if [ "$actualsum" != "$sha256sum" ] ; then
											>&2 echo error: SHA256 sum "$actualsum" of file "$fn" does not match expected sum "$sha256sum"
											exit 32
										fi
										for v in $python_versions ; do
											if [ "$diffs" == "1" ] ; then
												python"$v" `which pypipackage-to-srpm` --no-binary-rpms $epoch $mangle_name "$fn" *.diff
											else
												python"$v" `which pypipackage-to-srpm` --no-binary-rpms $epoch $mangle_name "$fn"
											fi
										done
									'''
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
						sh 'rm -rf -- *'
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
			stage('Publish') {
				steps {
					script {
						if (env.BRANCH_NAME == "master") {
							autouploadfedorarpms()
						}
					}
				}
			}
		}
		post {
			always {
				node('master') {
					script {
						if (fileExists("xunit.xml")) {
							junit 'xunit.xml'
						}
						funcs.announceEnd(currentBuild.currentResult)
					}
				}
			}
		}
	}
}
