// Generates * files from *.in files within the current directory.
// Variables @BUILD_*@ in the files will be replaced with the
// $BUILD_* variable values within the corresponding output files.
def call() {
	funcs.glob("*.in").each{
		suffix = ~/.in$/
		out = it - suffix
		substInfile(it, out)
	}
}
