ARG JDK_IMAGE=airbyte/airbyte-base-java-image:2.0.2
FROM ${JDK_IMAGE}

ARG VERSION=0.44.2

ENV APPLICATION airbyte-bootloader
ENV VERSION ${VERSION}

WORKDIR /app

ADD bin/${APPLICATION}-${VERSION}.tar /app


ENTRYPOINT ["/bin/bash", "-c", "${APPLICATION}-${VERSION}/bin/${APPLICATION}"]
