/* Copyright 2019 EPAM Systems.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at
http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.

See the License for the specific language governing permissions and
limitations under the License.*/

import com.epam.edp.removepipes.database.Database
import com.epam.edp.removepipes.openshift.OpenshiftResource
import com.epam.edp.removepipes.jenkins.JenkinsItem

def call() {

    def context = [:]

    node("master") {
        stage("Init") {
            final String EDP_DEPLOY_PROJECT = "edp-deploy"

            if (params.RESOURCES_VERSION_2) {
                context.stageCR = "stage.v2"
                context.pipelineCR = "cdpipeline.v2"
            } else {
                context.stageCR = "stage"
                context.pipelineCR = "cdpipeline"
            }

            context.projectName = System.getenv('JENKINS_UI_URL').substring(0, System.getenv('JENKINS_UI_URL')
                    .indexOf(".")).minus("https://jenkins-").minus("-edp-cicd")

            context.database = new Database(EDP_DEPLOY_PROJECT, this)
            context.database.init()

            if (!params.INTERACTIVE_MODE) {
                context.cdPipeline = params.CD_PIPELINE

                if (!context.database.getCdPipelines(context.projectName).contains(context.cdPipeline))
                    error "CD pipeline \"${context.cdPipeline}\" not found in the \"${context.projectName}\" project"

            } else {
                def cdPipelineChoices = []

                cdPipelineChoices.add(choice(choices: "${context.database.getCdPipelines(context.projectName).plus('No_deletion').join('\n')}", name: "CD_PIPELINE"))
                context.cdPipeline = input id: 'cdPipeline', message: 'CD pipeline you want to remove.',
                        parameters: cdPipelineChoices

                if (context.cdPipeline == "No_deletion")
                    error "Deletion aborted"
            }
        }
        stage("Remove pipeline stages") {
            context.database.getCdStages(context.projectName, context.cdPipeline).each { stage ->
                new OpenshiftResource(context.stageCR, "${context.cdPipeline}-${stage}", this).remove()
                context.database.removeCdStage(context.projectName, context.cdPipeline, stage, params.RESOURCES_VERSION_2)
                context.database.getCdPipelineApplications(context.projectName, context.cdPipeline, params.RESOURCES_VERSION_2).each { application ->
                    new OpenshiftResource("imagestream", "${context.cdPipeline}-${stage}-${application}-verified",
                            this).remove()
                }
            }
        }
        stage("Remove pipeline custom resource") {
            new OpenshiftResource(context.pipelineCR, context.cdPipeline, this).remove()
        }
        stage("Remove database entries") {
            context.database.removeCdPipeline(context.projectName, context.cdPipeline, params.RESOURCES_VERSION_2)
        }
        stage("Remove Jenkins deploy jobs folder") {
            new JenkinsItem("${context.cdPipeline}-cd-pipeline", this).remove()
        }
    }
}
