# Runs the Kernbeisser Swing app on a virtual X display, viewable in a browser via noVNC.
# The app itself is not baked in: build/deploy is mounted at /opt/kernbeisser-dist.
FROM eclipse-temurin:21-jdk-jammy

RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      xvfb x11vnc fluxbox novnc websockify \
      fontconfig fonts-dejavu-core libxrender1 libxtst6 libxi6 \
 && rm -rf /var/lib/apt/lists/*

COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

ENV DISPLAY=:0
# 6080 noVNC (browser), 5900 VNC, 5005 JDWP (remote debugging)
EXPOSE 6080 5900 5005
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
