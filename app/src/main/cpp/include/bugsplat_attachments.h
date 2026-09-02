#ifndef BUGSPLAT_ATTACHMENTS_H
#define BUGSPLAT_ATTACHMENTS_H

// Written by the SDK into the Crashpad database directory and read by
// libbugsplat_handler.so at crash time. One UTF-8 path per line.
static const char kAttachmentsListFileName[] = "bugsplat_attachments.list";

#endif  // BUGSPLAT_ATTACHMENTS_H
