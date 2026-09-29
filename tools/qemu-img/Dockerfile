FROM alpine:3.22@sha256:5291449c3df73caf6ed85e649dec1b9e818b39a5d8c871e97afc13e9cd5e8fa8
RUN apk add --no-cache build-base python3 py3-pip meson ninja pkgconf curl xz \
    glib-dev glib-static pcre2-dev pcre2-static libffi-dev zlib-dev zlib-static \
    zstd-dev zstd-static linux-headers
WORKDIR /build
ARG QEMU_VERSION=11.1.1
RUN curl -fL https://download.qemu.org/qemu-${QEMU_VERSION}.tar.xz -o qemu.tar.xz && echo "079ffbff8a7111bbc89022107cbabf3bbfd614d5fc9d7cc675991196aca12482  qemu.tar.xz" | sha256sum -c - && tar xf qemu.tar.xz
RUN cd qemu-${QEMU_VERSION} && ./configure --static --disable-system --disable-user \
    --enable-tools --disable-docs --disable-guest-agent \
    --disable-werror --without-default-features --enable-zstd \
    --extra-ldflags='-Wl,-z,max-page-size=16384' && ninja -C build qemu-img
RUN mkdir /out && cp qemu-${QEMU_VERSION}/build/qemu-img /out/libqemu-img.so \
    && strip /out/libqemu-img.so && cp qemu-${QEMU_VERSION}/COPYING /out/COPYING \
    && cp qemu.tar.xz /out/qemu-source.tar.xz
