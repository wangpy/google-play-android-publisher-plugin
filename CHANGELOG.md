# Version history

## 3.0
December 2, 2019

- Android App Bundles (AAB files) are now supported by the existing build steps (thanks to [andrewjapar](https://github.com/andrewjapar) and [Joe Hansche](https://github.com/jhansche))
- Expansion files uploaded during a build can be applied to other APKs uploaded in the same build (thanks to [Mikhail Korotetsky](https://github.com/mkorotetsky))
- Increased minimum Jenkins version to 2.138.4
- Migrated documentation and changelog to GitHub, as the Jenkins wiki is deprecated

## 2.0
July 17, 2019

- Upgraded to [v3](https://android-developers.googleblog.com/2019/03/changes-to-google-play-developer-api.html) of the Google Play Developer Publishing API (thanks to [Joe Hansche](https://github.com/jhansche))
- Fixed various potential NullPointerExceptions if no APKs had been uploaded already (thanks to [Kazuhide Takahashi](https://github.com/kazuhidet))
- Increased minimum Jenkins version to 2.60.3

## 1.8
June 3, 2018

- Enabled ability to upload to the "internal" track (thanks to [Serge Beauchamp](https://github.com/sergebeauchampGoogle))
- Allowed arbitrary percentage values to be used for staged rollouts
- Fixed potential NullPointerException if something went wrong ([JENKINS-49789](https://issues.jenkins-ci.org/browse/JENKINS-49789))

## 1.7
February 26, 2018

- [Fix security issue](https://jenkins.io/security/advisory/2018-02-26/)

## 1.6
January 18, 2018

- Added ability to upload ProGuard mapping files ([JENKINS-38731](https://issues.jenkins-ci.org/browse/JENKINS-38731))
- Made it possible to specify the Google Play credential dynamically ([JENKINS-38613](https://issues.jenkins-ci.org/browse/JENKINS-38613))
- Updated Google Play API dependency to hopefully make uploads a bit more stable
- Improved various error messages and configuration validation
- Increased minimum Jenkins version to 2.32
- Thanks to [Christopher Frieler](https://github.com/christopherfrieler) and [Dragan Marjanovic](https://github.com/dmarjanovic)

## 1.5
September 8, 2016

- Added support for the [Pipeline Plugin](https://wiki.jenkins.io/display/JENKINS/Pipeline+Plugin)

## 1.4.1
August 27, 2015

- Ensured that the required Token Macro Plugin is installed (see [JENKINS-29887](https://issues.jenkins-ci.org/browse/JENKINS-29887))

## 1.4
July 30, 2015

- Fixed the Google Play credential being forgotten when editing a job's configuration (see [JENKINS-26542](https://issues.jenkins-ci.org/browse/JENKINS-26542))
- Improved error messages when Google Play credentials are missing or misconfigured (see [JENKINS-25933](https://issues.jenkins-ci.org/browse/JENKINS-25933))
- Improved error messages shown when reading info from an APK fails
- Fixed or improved various aspects of job configuration validation
- Added additional logging about the credential ID and app ID being used
- Integrated the [Token Macro Plugin](https://wiki.jenkins.io/display/JENKINS/Token+Macro+Plugin) to give more options for specifying "Recent Changes" text (or any other field)
  - For example, with version 1.11 of token-macro, it will be possible to use something like `${FILE, path="changes-en.txt"}`, to read a changelog from the workspace and use its contents as release notes when uploading an APK to Google Play

## 1.3.1
April 1, 2015

- Improved the logging of which and how many APK files are to be uploaded/re-assigned

## 1.3
March 22, 2015

- Added new build step to enable moving existing APKs to a different release track

## 1.2
November 1, 2014

- Added ability to automatically recover from API failures when committing changes, if possible (see [JENKINS-25398](https://issues.jenkins-ci.org/browse/JENKINS-25398))

## 1.1
October 27, 2014

- Added ability to upload APK expansion files
- Upgraded APK parser library to fix some potential crashes

## 1.0
October 4, 2014

- Initial release
