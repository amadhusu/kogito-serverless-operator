openshift = null
container = null
properties = null
minikube = null

defaultImageParamsPrefix = 'IMAGE'
baseImageParamsPrefix = 'BASE_IMAGE'
promoteImageParamsPrefix = 'PROMOTE_IMAGE'

void initPipeline() {
    properties = load '.ci/jenkins/scripts/properties.groovy'

    openshift = load '.ci/jenkins/scripts/openshift.groovy'
    openshift.openshiftApiKey = env.OPENSHIFT_API_KEY
    openshift.openshiftApiCredsKey = env.OPENSHIFT_CREDS_KEY

    container = load '.ci/jenkins/scripts/container.groovy'
    container.containerEngine = env.CONTAINER_ENGINE
    container.containerTlsOptions = env.CONTAINER_TLS_OPTIONS
    container.containerOpenshift = openshift

    minikube = load '.ci/jenkins/scripts/minikube.groovy'
}

void updateDisplayName() {
    if (params.DISPLAY_NAME) {
        currentBuild.displayName = params.DISPLAY_NAME
    }
}

void cleanGoPath() {
    sh 'rm -rf $GOPATH/bin/*'
}

String buildTempOpenshiftImageFullName(boolean internal=false) {
    return "${getTempOpenshiftImageName(internal)}:${getTempTag()}"
}

String getTempOpenshiftImageName(boolean internal=false) {
    String registry = internal ? openshiftInternalRegistry : openshift.getOpenshiftRegistry()
    return "${registry}/openshift/${env.OPERATOR_IMAGE_NAME}"
}

String getTempTag() {
    return "pr-${getShortGitCommitHash()}"
}

void checkoutRepo(String repoName = '', String directory = '') {
    repoName = repoName ?: getRepoName()
    closure = {
        deleteDir()
        checkout(githubscm.resolveRepository(repoName, getGitAuthor(), getBuildBranch(), false))
        // need to manually checkout branch since on a detached branch after checkout command
        sh "git checkout ${getBuildBranch()}"
    }

    if (directory) {
        dir(directory, closure)
    } else {
        closure()
    }
}

void loginRegistry(String paramsPrefix = defaultImageParamsPrefix) {
    if (isImageInOpenshiftRegistry(paramsPrefix)) {
        container.loginOpenshiftRegistry()
    } else if (getImageRegistryCredentials(paramsPrefix)) {
        container.loginContainerRegistry(getImageRegistry(paramsPrefix), getImageRegistryCredentials(paramsPrefix))
    }
}

void createRelease() {
    if (isReleaseExist()) {
        deleteRelease()
    }

    if (githubscm.isTagExist('origin', getGitTag())) {
        githubscm.removeLocalTag(getGitTag())
        githubscm.removeRemoteTag('origin', getGitTag(), getGitAuthorCredsID())
    }

    def releaseName = "Kogito Serverless Operator ${getGitTag()}"
    def description = 'This is an Alpha release !!\n\nServerless Kogito Operator is a Kubernetes based operator for Serverless Workflow\' deployment.'
    def yamlInstaller = 'operator.yaml'
    withCredentials([string(credentialsId: env.GITHUB_TOKEN_CREDS_ID, variable: 'GITHUB_TOKEN')]) {
        sh """
            gh release create ${getGitTag()} --prerelease --target \"${getBuildBranch()}\" --title \"${releaseName}\" --notes \"${description}\" 
            sleep 10
            gh release upload ${getGitTag()} \"${yamlInstaller}\"
        """
    }
}

boolean isReleaseExist() {
    releaseExistStatus = -1
    withCredentials([string(credentialsId: env.GITHUB_TOKEN_CREDS_ID, variable: 'GITHUB_TOKEN')]) {
        releaseExistStatus = sh(returnStatus: true, script: """
            export GITHUB_USER=${getGitAuthor()}
            github-release info --tag ${getGitTag()}
        """)
    }
    return releaseExistStatus == 0
}

void deleteRelease() {
    withCredentials([string(credentialsId: env.GITHUB_TOKEN_CREDS_ID, variable: 'GITHUB_TOKEN')]) {
        sh """
            export GITHUB_USER=${getGitAuthor()}
            github-release delete --tag ${getGitTag()}
        """
    }
}

// Set images public on quay. Useful when new images are introduced.
void makeQuayImagePublic(String repository, String paramsPrefix = defaultImageParamsPrefix) {
    String namespace = getImageNamespace(paramsPrefix)
    echo "Check and set public if needed Quay repository ${namespace}/${repository}"
    try {
        cloud.makeQuayImagePublic(namespace, repository, [ usernamePassword: getImageRegistryCredentials(paramsPrefix)])
    } catch (err) {
        echo "[ERROR] Cannot set image quay.io/${namespace}/${repository} as visible"
    }
}

String getPropertiesImagePrefix() {
    return 'images'
}

String getImageRegistryProperty() {
    return contructImageProperty('registry')
}

String getImageNamespaceProperty() {
    return contructImageProperty('namespace')
}

String getImageNamePrefixProperty() {
    return contructImageProperty('name-prefix')
}

String getImageNameSuffixProperty() {
    return contructImageProperty('name-suffix')
}

String getImageNamesProperty() {
    return contructImageProperty('names')
}

String getImageTagProperty() {
    return contructImageProperty('tag')
}

String contructImageProperty(String suffix) {
    return "${getPropertiesImagePrefix()}.${suffix}"
}

////////////////////////////////////////////////////////////////////////
// Image information
////////////////////////////////////////////////////////////////////////

boolean isImageInOpenshiftRegistry(String paramsPrefix = defaultImageParamsPrefix) {
    return params[constructKey(paramsPrefix, 'USE_OPENSHIFT_REGISTRY')]
}

String getImageRegistryCredentials(String paramsPrefix = defaultImageParamsPrefix) {
    return isImageInOpenshiftRegistry(paramsPrefix) ? '' : params[constructKey(paramsPrefix, 'REGISTRY_CREDENTIALS')]
}

String getImageRegistry(String paramsPrefix = defaultImageParamsPrefix) {
    if (isImageInOpenshiftRegistry(paramsPrefix)) {
        return openshift.getOpenshiftRegistry()
    } else if (paramsPrefix == baseImageParamsPrefix && properties.contains(getImageRegistryProperty())) {
        return properties.retrieve(getImageRegistryProperty())
    }
    return  params[constructKey(paramsPrefix, 'REGISTRY')]
}

String getImageNamespace(String paramsPrefix = defaultImageParamsPrefix) {
    if (isImageInOpenshiftRegistry(paramsPrefix)) {
        return 'openshift'
    } else if (paramsPrefix == baseImageParamsPrefix && properties.contains(getImageNamespaceProperty())) {
        return properties.retrieve(getImageNamespaceProperty())
    }
    return params[constructKey(paramsPrefix, 'NAMESPACE')]
}

String getImageNamePrefix(String paramsPrefix = defaultImageParamsPrefix) {
    if (paramsPrefix == baseImageParamsPrefix && properties.contains(getImageNamePrefixProperty())) {
        return properties.retrieve(getImageNamePrefixProperty())
    }
    return params[constructKey(paramsPrefix, 'NAME_PREFIX')]
}

List getImageNames(String paramsPrefix = defaultImageParamsPrefix) {
    String commaSepImages = ''
    if (paramsPrefix == baseImageParamsPrefix && properties.contains(getImageNamesProperty())) {
        commaSepImages = properties.retrieve(getImageNamesProperty())
    } else {
        commaSepImages = params[constructKey(paramsPrefix, 'NAMES')]
    }
    return commaSepImages.split(',') as List
}

String getImageNameSuffix(String paramsPrefix = defaultImageParamsPrefix) {
    if (paramsPrefix == baseImageParamsPrefix && properties.contains(getImageNameSuffixProperty())) {
        return properties.retrieve(getImageNameSuffixProperty())
    }
    return params[constructKey(paramsPrefix, 'NAME_SUFFIX')]
}

String getFullImageName(String imageName, String paramsPrefix = defaultImageParamsPrefix) {
    prefix = getImageNamePrefix(paramsPrefix)
    suffix = getImageNameSuffix(paramsPrefix)
    return (prefix ? prefix + '-' : '') + imageName + (suffix ? '-' + suffix : '')
}

String getImageTag(String paramsPrefix = defaultImageParamsPrefix) {
    if (paramsPrefix == baseImageParamsPrefix && properties.contains(getImageTagProperty())) {
        return properties.retrieve(getImageTagProperty())
    }
    return params[constructKey(paramsPrefix, 'TAG')] ?: getShortGitCommitHash()
}

String getImageFullTag(String imageName, String paramsPrefix = defaultImageParamsPrefix, String tag = '') {
    String fullTag = getImageRegistry(paramsPrefix)
    fullTag += "/${getImageNamespace(paramsPrefix)}"
    fullTag += "/${getFullImageName(imageName, paramsPrefix)}"
    fullTag += ":${tag ?: getImageTag(paramsPrefix)}"
    return fullTag
}

String constructKey(String keyPrefix, String key) {
    return keyPrefix ? "${keyPrefix}_${key}" : key
}

String getShortGitCommitHash() {
    return sh(returnStdout: true, script: 'git rev-parse --short HEAD').trim()
}

String getReducedTag(String paramsPrefix = defaultImageParamsPrefix) {
    String tag = helper.getImageTag(paramsPrefix)
    try {
        String[] versionSplit = tag.split('\\.')
        return "${versionSplit[0]}.${versionSplit[1]}"
    } catch (error) {
        echo "${tag} cannot be reduced to the format X.Y"
    }
    return ''
}

/////////////////////////////////////////////////////////////////////
// Utils

boolean isRelease() {
    return env.RELEASE && env.RELEASE.toBoolean()
}

boolean isCreatePr() {
    return params.CREATE_PR
}

String getRepoName() {
    return env.REPO_NAME
}

String getBuildBranch() {
    return params.BUILD_BRANCH_NAME
}

String getGitAuthor() {
    return "${GIT_AUTHOR}"
}

String getGitAuthorCredsID() {
    return env.AUTHOR_CREDS_ID
}

String getPRBranch() {
    return "${getProjectVersion()}-${env.BOT_BRANCH_HASH}"
}

String getProjectVersion() {
    return properties.retrieve('project.version') ?: params.PROJECT_VERSION
}

String getNextVersion() {
    return util.getNextVersion(getProjectVersion(), 'micro', 'snapshot')
}

String getSnapshotBranch() {
    return "${getNextVersion()}-${env.BOT_BRANCH_HASH}"
}

boolean shouldLaunchTests() {
    return !params.SKIP_TESTS
}

String getDeployPropertiesFileUrl() {
    String url = params.DEPLOY_BUILD_URL
    if (url) {
        return "${url}${url.endsWith('/') ? '' : '/'}artifact/${env.PROPERTIES_FILE_NAME}"
    }
    return ''
}

String getGitTag() {
    return params.GIT_TAG != '' ? params.GIT_TAG : "v${getProjectVersion()}"
}

boolean isDeployLatestTag() {
    return params.DEPLOY_WITH_LATEST_TAG
}

return this
