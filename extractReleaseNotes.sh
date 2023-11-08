TAG_NAME=${GITHUB_REF#refs/tags/v}  # Strips 'refs/tags/v' from the tag name
ESCAPED_TAG_NAME=$(echo $TAG_NAME | sed 's/[][*^$.\]/\\&/g')  # Escapes regex characters
RELEASE_NOTES=$(awk "\".*\" '/^### $ESCAPED_TAG_NAME/{flag=1; next} /^##/{flag=0} flag'" README.md)
RELEASE_NOTES="${RELEASE_NOTES//'%'/'%25'}"
RELEASE_NOTES="${RELEASE_NOTES//$'\n'/'%0A'}"
RELEASE_NOTES="${RELEASE_NOTES//$'\r'/'%0D'}"
echo "notes::$RELEASE_NOTES" >> $GITHUB_OUTPUT