/*
 * Copyright 2022 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kubernetes

import (
	"context"

	"github.com/pkg/errors"

	"github.com/kiegroup/kogito-serverless-operator/container-builder/api"
)

func newInitializePodAction() Action {
	return &initializePodAction{}
}

type initializePodAction struct {
	baseAction
}

// Name returns a common name of the action.
func (action *initializePodAction) Name() string {
	return "initialize-pod"
}

// CanHandle tells whether this action can handle the build.
func (action *initializePodAction) CanHandle(build *api.Build) bool {
	return build.Status.Phase == "" || build.Status.Phase == api.BuildPhaseInitialization
}

// Handle handles the builds.
func (action *initializePodAction) Handle(ctx context.Context, build *api.Build) (*api.Build, error) {
	if err := deleteBuilderPod(ctx, action.client, build); err != nil {
		return nil, errors.Wrap(err, "cannot delete build pod")
	}

	pod, err := getBuilderPod(ctx, action.client, build)
	if err != nil || pod != nil {
		// We return and wait for the pod to be deleted before de-queue the build pod.
		return nil, err
	}

	build.Status.Phase = api.BuildPhaseScheduling

	return build, nil
}
