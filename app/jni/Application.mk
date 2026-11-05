# Built with NDK 19.2.5345600
#APP_ABI      := armeabi-v7a x86 arm64-v8a x86_64
# SW: arm64 only for now
APP_ABI       := arm64-v8a
APP_PLATFORM := android-19
APP_STL      := c++_static
APP_CPPFLAGS += -fexceptions
APP_OPTIM    := debug
