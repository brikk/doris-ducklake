# BE overlay image for the DuckLake-on-Doris live smoke.
#
# Overlays our locally-built master BE (`output/be`, from apache/doris master at
# the current re-vendor pin — see ../fe-patches/FE-PATCHES.md "Re-vendor log")
# onto the stock released BE image, so the compose cluster runs a master BE
# (needed for master-only behaviour: §8b count-from-manifests, §12b DEFAULT
# backfill, writes, §13 GC) while inheriting the base image's init scripts / JDK
# / entrypoint. Mirrors ../fe-overlay/Dockerfile.
#
# Build the BE first (inside the matching build-env container), then bake:
#   # 1) build BE  → ~/DEV/OSS/doris/output/be
#   docker run --rm --network host --user "$(id -u):$(id -g)" -e HOME=/home/jayson \
#     -v /home/jayson/DEV/OSS/doris:/doris -v /tmp/opencode/be-ccache:/ccache \
#     -v /tmp/opencode/be-m2:/home/jayson/.m2 \
#     --entrypoint /bin/bash apache/doris:build-env-ldb-toolchain-latest -c '
 #       export DORIS_THIRDPARTY=/var/local/thirdparty CCACHE_DIR=/ccache
 #       cd /doris && sh build.sh --be -j14'
 #   # NOTE: cap at -j14 (NOT $(nproc)=24). On the 26 GB dev box (14 GB of which is a
 #   # tmpfs /tmp), a full 24-core BE compile OOM-rebooted the machine — especially if any
 #   # other heavy JVM (e.g. the gradle suite) runs at the same time. Never parallelize the
 #   # BE compile with another heavy build; keep it sequential and -j14.
#   # 2) stage a minimal context (don't send the multi-GB repo to the daemon) + bake
#   S=/tmp/beimg; rm -rf $S; mkdir -p $S/output; cp -r ~/DEV/OSS/doris/output/be $S/output/be
#   docker build --platform linux/amd64 \
#     -f ~/DEV/brikk/doris-ducklake/compose/be-overlay/Dockerfile \
#     -t doris-be:master-local \
#     --build-arg BASE_IMAGE=apache/doris:be-4.1.3 --build-arg OUTPUT_PATH=./output $S

ARG BASE_IMAGE=apache/doris:be-4.1.3
FROM ${BASE_IMAGE}

ARG OUTPUT_PATH=./output

# DORIS_BE_HOME/be in the base image. Wipe the base build artifacts we replace,
# then overlay ours. init_be.sh (in /opt/apache-doris) + the JDK come from the base.
ENV DORIS_BE_HOME=/opt/apache-doris/be

RUN rm -rf ${DORIS_BE_HOME}/bin ${DORIS_BE_HOME}/lib ${DORIS_BE_HOME}/conf \
           ${DORIS_BE_HOME}/www

COPY ${OUTPUT_PATH}/be/bin  ${DORIS_BE_HOME}/bin
COPY ${OUTPUT_PATH}/be/lib  ${DORIS_BE_HOME}/lib
COPY ${OUTPUT_PATH}/be/conf ${DORIS_BE_HOME}/conf
COPY ${OUTPUT_PATH}/be/www  ${DORIS_BE_HOME}/www

RUN chmod 550 ${DORIS_BE_HOME}/lib/doris_be || true
