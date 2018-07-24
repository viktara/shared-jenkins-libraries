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
    comboBuilder(task, axisList, 0)
    tasks.sort { it.key }
    return tasks
}
