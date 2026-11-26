LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := libtermux-bootstrap
LOCAL_SRC_FILES := termux-bootstrap-zip.S termux-bootstrap.c
ifeq ($(AGY_LITE_UPDATE),1)
LOCAL_CFLAGS += -DAGY_LITE_UPDATE=1
LOCAL_ASFLAGS += -DAGY_LITE_UPDATE=1
endif
include $(BUILD_SHARED_LIBRARY)
