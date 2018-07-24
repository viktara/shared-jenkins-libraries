def combo(task, axes) {
    def tasks = [:]
    def comboEntry = []
    def comboBuilder
    comboBuilder = {
        def t, a, int level -> for ( entry in a[0] ) {
            comboEntry[level] = entry
            if (a.size() > 1) {
                comboBuilder(a.drop(1), level + 1)
            }
            else {
                tasks[comboEntry.join("_")] = t(comboEntry.collect())
            }
        }
    }
    comboBuilder(task, axes, 0)
    tasks.sort { it.key }
    return tasks
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

def shortCommitId(path) {
    sh (
        script: "cd ${path} && git rev-parse --short HEAD",
        returnStdout: true
    ).trim()
}
