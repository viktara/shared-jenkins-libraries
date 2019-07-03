// Generates a file X from a file X.in within the current directory.
// Variables @BUILD_*@ in the files will be replaced with the
// $BUILD_* variable values within the corresponding output files.
def call(String infile, String outfile) {
	withEnv(["in=${infile}", "out=${outfile}"]) {
		sh '''#!/bin/bash -e
		>&2 echo "Substituting: $in -> $out"
		cat "$in" > "$out"
		for variable in $(env | cut -d = -f 1 | grep ^BUILD_ || true) ; do
			varcontents="${!variable}"
			python3 -c "
import sys
s = open(sys.argv[2]).read()
ss = s.replace(sys.argv[3], sys.argv[4])
if s != ss:
	print('Substituting: %s -> %s: %r gets substituted for %r' % (sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]), file=sys.stderr)
open(sys.argv[2], 'w').write(ss)" \
				"$in" "$out" "@$variable@" "$varcontents"
		done
		if cmp "$in" "$out" >/dev/null 2>&1 ; then
			>&2 echo "Nothing to substitute in $in.  Removing $out."
			rm -f "$out"
		else
			>&2 echo "Substituted: $in -> $out"
		fi
		'''
	}
}
