if [ "$MAVEN_USER" = "" ]; then
   echo "You must set MAVEN_USER and MAVEN_PASSWORD"
   exit
fi

bazel run //:jnb_ping_maven.publish \
  --define gpg_sign=true \
  --define maven_repo=https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/

echo "Sleeping 60 seconds before finalizing..."
sleep 60

# 2. Finalize the upload (must be from the SAME machine/IP)
curl -X POST \
  "https://ossrh-staging-api.central.sonatype.com/manual/upload/defaultRepository/com.zaxxer" \
  -H "Authorization: Bearer $(echo -n "$MAVEN_USER:$MAVEN_PASSWORD" | base64)" \
  -H "Content-Type: application/json" \
  -d '{"publishing_type": "user_managed"}'

