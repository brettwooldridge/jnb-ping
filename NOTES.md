## How to publish (note to self)

```
export ORG_GRADLE_PROJECT_sonatypeUsername=<username>
export ORG_GRADLE_PROJECT_sonatypePassword=<password>

./gradlew publishToSonatype closeAndReleaseSonatypeStagingRepository
```
