/**
 * Copyright (C) 2018 Alauda.io
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.alauda.jenkins.devops.sync;

import hudson.Extension;
import hudson.model.Action;
import hudson.model.ParameterValue;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.ParametersAction;
import hudson.model.Queue;

import io.alauda.jenkins.devops.sync.listener.PipelineSyncRunListener;
import io.alauda.jenkins.devops.sync.util.AlaudaUtils;
import io.alauda.jenkins.devops.sync.util.PipelineToActionMapper;
import io.alauda.kubernetes.api.model.Pipeline;
import io.alauda.kubernetes.api.model.PipelineConfig;
import io.alauda.kubernetes.client.KubernetesClientException;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

@Extension
public class PipelineDecisionHandler extends Queue.QueueDecisionHandler {

  private static final Logger LOGGER = Logger.getLogger(PipelineDecisionHandler.class.getName());

  @Override
  public boolean shouldSchedule(Queue.Task p, List<Action> actions) {
    if (p instanceof WorkflowJob && !isAlaudaDevOpsPipelineCause(actions)) {
      WorkflowJob wj = (WorkflowJob) p;
      String taskName = p.getName();
      PipelineConfigProjectProperty pipelineConfigProjectProperty = wj
        .getProperty(PipelineConfigProjectProperty.class);
      if (pipelineConfigProjectProperty != null
        && StringUtils.isNotBlank(pipelineConfigProjectProperty
        .getNamespace())
        && StringUtils.isNotBlank(pipelineConfigProjectProperty
        .getName())) {

        String namespace = pipelineConfigProjectProperty.getNamespace();
        String jobURL = PipelineSyncRunListener.joinPaths(
          AlaudaUtils.getJenkinsURL(AlaudaUtils.getAuthenticatedAlaudaClient(),
            namespace), wj.getUrl());

        LOGGER.info("Got this namespace " + namespace + " from this pipelineConfigProjectProperty: " + pipelineConfigProjectProperty + " with run policy: " + pipelineConfigProjectProperty.getPipelineRunPolicy());
        // TODO: Add trigger API for pipelineconfig (like above)
        PipelineConfig config = AlaudaUtils.getAuthenticatedAlaudaClient()
          .pipelineConfigs()
          .inNamespace(namespace)
          .withName(pipelineConfigProjectProperty.getName()).get();
        if (config == null) {
          LOGGER.warning("Config is null");
          return false;
        } else if (config.getMetadata() == null) {
          LOGGER.warning("Config metadata is null");
          return false;
        }

        Pipeline pipeline = null;
        try {
          pipeline = PipelineGenerator.buildPipeline(config, jobURL, actions);
        } catch (KubernetesClientException e) {
          LOGGER.warning(config.getMetadata().getName() + " got error : " + e.getMessage());
          return false;
        }

        ParametersAction params = dumpParams(actions);
        if(params != null) {
          LOGGER.fine("ParametersAction: " + params.toString());
          PipelineToActionMapper.addParameterAction(pipeline.getMetadata().getName(), params);
        } else {
          LOGGER.fine("The param is null in task : " + taskName);
        }

        CauseAction cause = dumpCause(actions);
        if(cause != null) {
          LOGGER.fine("get CauseAction: "+  cause.getDisplayName());
          for (Cause c : cause.getCauses()) {
            LOGGER.fine("Cause: " + c.getShortDescription());
          }

          PipelineToActionMapper.addCauseAction(pipeline.getMetadata().getName(), cause);
        } else {
          LOGGER.fine("Get null CauseAction in task : " + taskName);
        }

        return false;
      }
    }

    return true;
  }

  private static boolean isAlaudaDevOpsPipelineCause(List<Action> actions) {
    for (Action action : actions) {
      if (action instanceof CauseAction) {
        CauseAction causeAction = (CauseAction) action;
        for (Cause cause : causeAction.getCauses()) {
          if (cause instanceof JenkinsPipelineCause) {
            return true;
          }
        }
      }
    }
    return false;
  }

  private static CauseAction dumpCause(List<Action> actions) {
    for (Action action : actions) {
      if (action instanceof CauseAction) {
        CauseAction causeAction = (CauseAction) action;
        if (LOGGER.isLoggable(Level.FINE)) {
          for (Cause cause : causeAction.getCauses()) {
            LOGGER.fine("cause: " + cause.getShortDescription());
          }
        }

        return causeAction;
      }
    }
    return null;
  }

  private static ParametersAction dumpParams(List<Action> actions) {
    for (Action action : actions) {
      if (action instanceof ParametersAction) {
        ParametersAction paramAction = (ParametersAction) action;
        if (LOGGER.isLoggable(Level.FINE))
          for (ParameterValue param : paramAction.getAllParameters()) {
            LOGGER.fine("param name " + param.getName()
              + " param value " + param.getValue());
          }
        return paramAction;
      }
    }
    return null;
  }

}