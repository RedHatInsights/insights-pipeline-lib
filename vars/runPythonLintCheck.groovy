// Run a lint check using either flake8 or pylama (with the pytest plugin)


def call(parameters = [:]) {
    pylama = parameters.get('pylama', False)

    withStatusContext.lint {
        if (pylama) {
            sh "${pipelineVars.userPath}/pipenv run python -m pytest --pylama --junitxml=lint-results.xml --ignore=tests/"
        } else {
            sh "${pipelineVars.userPath}/pipenv run flake8 advisor/api/ --output-file lint-results.txt"
            sh "${pipelineVars.userPath}/pipenv run flake8_junit lint-results.txt lint-results.xml"
        }
    }

    try {
        junit 'lint-results.xml'
    } catch (evalErr) {
        // allow the unit tests to run even if evaluating lint results failed...
        echo evalErr.getMessage()
    }
}
