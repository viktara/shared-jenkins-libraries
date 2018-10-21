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

def call() {
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
					dir('src') {
						checkout scm
						sh 'git clean -fxd'
					}
				}
			}
			stage('SRPM') {
				steps {
					dir('src') {
						sh '''
							set -e
							if test -f setup.py ; then
								rm -rf build dist
								if [ $(find . -name '*_test.py' -o -name 'test_*.py' | wc -l) != 0 ] ; then
									nosetests -v
								fi
								python setup.py bdist_rpm
								mv dist/*.src.rpm .
								rm -rf build dist
							else
								make srpm
							fi
						'''
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
						autouploadfedorarpms()
					}
				}
			}
		}
		post {
			always {
				node('master') {
					script {
						funcs.announceEnd(currentBuild.currentResult)
					}
				}
			}
		}
	}
}
