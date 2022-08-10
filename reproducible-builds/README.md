# Reproducible Builds


This document has been updated for the fork


## Fork changelog

2021-09-16: Upstream has updated gradle and gradle warmer to newer versions, so reproducible builds is the same as upstream again.
2021-09-09: Updated gradle warmer to match main gradle wrapper (the actual main gradle wrapper was updated upstream a few months back).

## TL;DR

```bash
# Clone the Signal Android source repository
git clone https://github.com/shadowmoon-waltz/Signal-Android.git && cd Signal-Android

# Check out the release tag for the version you'd like to compare
git checkout v[the version number]

# Build the Docker image
cd reproducible-builds
docker build -t signal-android .

# Go back up to the root of the project
cd ..

# Build using the Docker environment (non-fork uses assemblePlayProdRelease instead of assembleSwProdRelease)
docker run --rm -v $(pwd):/project -w /project signal-android ./gradlew clean assembleSwProdRelease

# Verify the APKs (you compare against GitHub release apks, since we don't publish to Play Store at the moment)
python3 apkdiff/apkdiff.py app/build/outputs/apks/project-release-unsigned.apk path/to/SignalFromGitHubReleases.apk
```

***


## Introduction

Since version 3.15.0 Signal for Android has supported reproducible builds. The instructions were then updated for version 5.0.0. This is achieved by replicating the build environment as a Docker image. You'll need to build the image, run a container instance of it, compile Signal inside the container and finally compare the resulted APK to the APK that is distributed on GitHub release pages (not the Google Play Store like non-fork).

The command line parts in this guide are written for Linux but with some little modifications you can adapt them to macOS (OS X) and Windows. In the following sections we will use `3.15.2` as an example Signal version. You'll just need to replace all occurrences of `3.15.2` with the version number you are about to verify.


## Setting up directories

First let's create a new directory for this whole reproducible builds project. In your home folder (`~`), create a new directory called `reproducible-signal`.

```bash
mkdir ~/reproducible-signal
```

Next create another directory inside `reproducible-signal` called `apk-from-github-releases`.

```bash
mkdir ~/reproducible-signal/apk-from-github-releases
```

We will use this directory to share APKs between the host OS and the Docker container.


## Getting the installed version of Signal APK

To compare the APKs we of course need a version of Signal to compare against. Just download one from the GitHub releases page and put it into `~/reproducible-signal/apk-from-github-releases/` with the name `SignalSW-<version>.apk`, to match the non-fork version of this guide.

We will use this APK in the final part when we compare it with the self-built APK using the source from GitHub.

## Identifying the ABI

Since v4.37.0, the APKs have been split by ABI, the CPU architecture of the target device. You pick which one to install based on what the apk name ends in. The five versions are currently `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`, and `universal` (includes support for all the other ABIs).

## Installing Docker

Install Docker by following the instructions for your platform at https://docs.docker.com/engine/installation/

Your platform might also have its own preferred way of installing Docker. E.g. Ubuntu has its own Docker package (`docker.io`) if you do not want to follow Docker's instructions.

In the following sections we will assume that your Docker installation works without issues. So after installing, please make sure that everything is running smoothly before continuing.

### Configuring Docker runtime memory

The non-fork version of this section states: Docker seems to require at least 5GB runtime memory to be able to build the APK successfully. Docker behaves differently on each platform - please consult Docker documentation for the platform of choice to configure the runtime memory settings.

I have been able to get it to build with 3GB runtime memory.

 * https://docs.docker.com/config/containers/resource_constraints/
 * OS X https://docs.docker.com/docker-for-mac/#resources
 * Windows https://docs.docker.com/docker-for-windows/#resources

## Building a Docker image for Signal
First, you need to pull down the source for Signal-Android, which contains everything you need to build the project, including the `Dockerfile`. The `Dockerfile` contains instructions on how to automatically build a Docker image for Signal. It's located in the `reproducible-builds` directory of the repository. To get it, clone the project:

```
git clone https://github.com/shadowmoon-waltz/Signal-Android.git signal-source
```

Then, checkout the specific version you're trying to build:

```
git checkout --quiet v5.0.0
```

Then, to build it, go into the `reproducible-builds` directory:

```
cd ~/reproducible-signal/signal-source/reproducible-builds
```

...and run the docker build command:

```
docker build -t signal-android .
```

(Note that there is a dot at the end of that command!)

Wait a few years for the build to finish... :construction_worker:

(Depending on your computer and network connection, this may take several minutes.)

:calendar: :sleeping:

After the build has finished, you may wish to list all your Docker images to see that it's really there:

```
docker images
```

Output should look something like this:

```
REPOSITORY          TAG                 IMAGE ID            CREATED             VIRTUAL SIZE
signal-android      latest              c6b84450b896        46 seconds ago      2.94 GB
```


## Compiling Signal inside a container

Next we compile Signal.

First go to the directory where the source code is: `reproducible-signal/signal-source`:

```
cd ~/reproducible-signal/signal-source
```

To build with the docker image you just built (`signal-android`), run:

```
docker run --rm -v $(pwd):/project -w /project signal-android ./gradlew clean assembleSwProdRelease
```

This will take a few minutes :sleeping:


### Checking if the APKs match

So now we can compare the APKs using the `apkdiff.py` tool.

The above build step produced several APKs, one for each supported ABI and one universal one. You will need to determine the correct APK to compare.

The ABIs currently available on the GitHub release pages are `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`, and `universal` (includes support for all the other ABIs).

Once you have determined the ABI, add an `abi` environment variable. For example, suppose we determine that `armeabi-v7a` is the ABI google play has served:

```bash
export abi=armeabi-v7a
```

And run the diff script to compare (updating the filenames for your specific version):

```bash
python3 reproducible-builds/apkdiff/apkdiff.py \
        app/build/outputs/apk/swProd/release/*sw-prod-$abi-release-unsigned*.apk \
        ../apk-from-github-releases/SignalSW-5.0.0.apk
```

Output:

```
APKs match!
```

If you get `APKs match!`, you have successfully verified that the GitHub release version matches with your own self-built version of Signal. Congratulations! Your APKs are a match made in heaven! :sparkles:

If you get `APKs don't match!`, you did something wrong in the previous steps. See the [Troubleshooting section](#troubleshooting) for more info.


## Comparing next time

If the build environment (i.e. `Dockerfile`) has not changed, you don't need to build the image again to verify a newer APK. You can just [run the container again](#compiling-signal-inside-a-container).


## Troubleshooting

Some common issues why things may not work:
- the Android packages in the Docker image are outdated and compiling Signal fails
- you built the Docker image with a wrong version of the `Dockerfile`
- you didn't checkout the correct Signal version tag with Git before compiling
- the ABI you selected is not the correct ABI, particularly if you see an error along the lines of `Sorted manifests don't match, lib/x86/libcurve25519.so vs lib/armeabi-v7a/libcurve25519.so`.
- this guide is outdated
- you are and/or I am in a dream
- if you run into this issue: https://issuetracker.google.com/issues/110237303 try to add `resources.arsc` to the list of ignored files and compare again
- I, the one maintaining this fork, made a mistake during the build process (wrong version of dependencies, etc.)
