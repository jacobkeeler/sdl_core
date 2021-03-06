/*
 * Copyright (c) 2014, Ford Motor Company
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following
 * disclaimer in the documentation and/or other materials provided with the
 * distribution.
 *
 * Neither the name of the Ford Motor Company nor the names of its contributors
 * may be used to endorse or promote products derived from this software
 * without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

#ifndef SRC_COMPONENTS_MEDIA_MANAGER_INCLUDE_MEDIA_MANAGER_MEDIA_MANAGER_H_
#define SRC_COMPONENTS_MEDIA_MANAGER_INCLUDE_MEDIA_MANAGER_MEDIA_MANAGER_H_

#include <string>
#include "protocol_handler/protocol_handler.h"

namespace media_manager {

class MediaManager {
  public:
    virtual void PlayA2DPSource(int32_t application_key) = 0;
    virtual void StopA2DPSource(int32_t application_key) = 0;

    virtual void StartMicrophoneRecording(int32_t application_key,
                                          const std::string& outputFileName,
                                          int32_t duration) = 0;
    virtual void StopMicrophoneRecording(int32_t application_key) = 0;

    virtual void StartStreaming(
        int32_t application_key, protocol_handler::ServiceType service_type) = 0;
    virtual void StopStreaming(
        int32_t application_key, protocol_handler::ServiceType service_type) = 0;

    virtual ~MediaManager(){}
};

}  // namespace media_manager

#endif  // SRC_COMPONENTS_MEDIA_MANAGER_INCLUDE_MEDIA_MANAGER_MEDIA_MANAGER_H_
