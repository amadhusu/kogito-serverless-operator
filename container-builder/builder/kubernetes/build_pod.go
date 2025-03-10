package kubernetes

import (
	"context"
	"os"
	"path"
	"strings"

	"github.com/pkg/errors"
	k8serrors "k8s.io/apimachinery/pkg/api/errors"
	"k8s.io/apimachinery/pkg/types"

	"github.com/kiegroup/kogito-serverless-operator/container-builder/client"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"

	"github.com/kiegroup/kogito-serverless-operator/container-builder/api"
)

type registrySecret struct {
	fileName    string
	mountPath   string
	destination string
	refEnv      string
}

func newBuildPod(ctx context.Context, c client.Client, build *api.Build) (*corev1.Pod, error) {
	pod := &corev1.Pod{
		TypeMeta: metav1.TypeMeta{
			APIVersion: corev1.SchemeGroupVersion.String(),
			Kind:       "Pod",
		},
		ObjectMeta: metav1.ObjectMeta{
			Namespace: build.Namespace,
			Name:      buildPodName(build),
			Labels: map[string]string{
				"kie.kogito.org/buildContext": build.Name,
				"kie.kogito.org/component":    "builder",
			},
		},
		Spec: corev1.PodSpec{
			RestartPolicy: corev1.RestartPolicyNever,
		},
	}

	for _, task := range build.Spec.Tasks {
		switch {
		case task.Kaniko != nil:
			err := addKanikoTaskToPod(ctx, c, build, task.Kaniko, pod)
			if err != nil {
				return nil, err
			}
		}
	}

	return pod, nil
}

func buildPodName(build *api.Build) string {
	return "kogito-" + strings.ToLower(build.Name) + "-builder"
}

func getBuilderPod(ctx context.Context, c client.Client, build *api.Build) (*corev1.Pod, error) {
	pod := corev1.Pod{}
	err := c.Get(ctx, types.NamespacedName{Name: buildPodName(build), Namespace: build.Namespace}, &pod)
	if err != nil && k8serrors.IsNotFound(err) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}

	return &pod, nil
}

func deleteBuilderPod(ctx context.Context, c client.Client, build *api.Build) error {
	pod := corev1.Pod{
		TypeMeta: metav1.TypeMeta{
			APIVersion: corev1.SchemeGroupVersion.String(),
			Kind:       "Pod",
		},
		ObjectMeta: metav1.ObjectMeta{
			Namespace: build.Namespace,
			Name:      buildPodName(build),
		},
	}

	err := c.Delete(ctx, &pod)
	if err != nil && k8serrors.IsNotFound(err) {
		return nil
	}

	return err
}

func getRegistrySecret(ctx context.Context, c client.Client, ns, name string, registrySecrets []registrySecret) (registrySecret, error) {
	secret := corev1.Secret{}
	err := c.Get(ctx, types.NamespacedName{Name: name, Namespace: ns}, &secret)
	if err != nil {
		return registrySecret{}, err
	}
	for _, k := range registrySecrets {
		if _, ok := secret.Data[k.fileName]; ok {
			return k, nil
		}
	}
	return registrySecret{}, errors.New("unsupported secret type for registry authentication")
}

func addRegistrySecret(name string, secret registrySecret, volumes *[]corev1.Volume, volumeMounts *[]corev1.VolumeMount, env *[]corev1.EnvVar) {
	*volumes = append(*volumes, corev1.Volume{
		Name: "registry-secret",
		VolumeSource: corev1.VolumeSource{
			Secret: &corev1.SecretVolumeSource{
				SecretName: name,
				Items: []corev1.KeyToPath{
					{
						Key:  secret.fileName,
						Path: secret.destination,
					},
				},
			},
		},
	})

	*volumeMounts = append(*volumeMounts, corev1.VolumeMount{
		Name:      "registry-secret",
		MountPath: secret.mountPath,
		ReadOnly:  true,
	})

	if secret.refEnv != "" {
		*env = append(*env, corev1.EnvVar{
			Name:  secret.refEnv,
			Value: path.Join(secret.mountPath, secret.destination),
		})
	}
}

func proxyFromEnvironment() []corev1.EnvVar {
	var envVars []corev1.EnvVar

	if httpProxy, ok := os.LookupEnv("HTTP_PROXY"); ok {
		envVars = append(envVars, corev1.EnvVar{
			Name:  "HTTP_PROXY",
			Value: httpProxy,
		})
	}

	if httpsProxy, ok := os.LookupEnv("HTTPS_PROXY"); ok {
		envVars = append(envVars, corev1.EnvVar{
			Name:  "HTTPS_PROXY",
			Value: httpsProxy,
		})
	}

	if noProxy, ok := os.LookupEnv("NO_PROXY"); ok {
		envVars = append(envVars, corev1.EnvVar{
			Name:  "NO_PROXY",
			Value: noProxy,
		})
	}

	return envVars
}
