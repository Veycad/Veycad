# Apply only to the verified pinned source, producing a private build-directory copy.
# Timestamp classes after the actual audio end are suppressed before sampling;
# neither JNI rows nor observed token intervals are clipped after recognition.
if(NOT DEFINED VEYCAD_WHISPER_UPSTREAM OR NOT DEFINED VEYCAD_WHISPER_OUTPUT)
    message(FATAL_ERROR "Whisper audio-bounds patch requires input and output paths")
endif()
get_filename_component(_veycad_input "${VEYCAD_WHISPER_UPSTREAM}" ABSOLUTE)
get_filename_component(_veycad_output "${VEYCAD_WHISPER_OUTPUT}" ABSOLUTE)
file(REAL_PATH "${_veycad_input}" _veycad_input_real)
file(REAL_PATH "${_veycad_output}" _veycad_output_real)
if(WIN32)
    string(TOLOWER "${_veycad_input_real}" _veycad_input_real)
    string(TOLOWER "${_veycad_output_real}" _veycad_output_real)
endif()
if(_veycad_input STREQUAL _veycad_output OR _veycad_input_real STREQUAL _veycad_output_real)
    message(FATAL_ERROR "Whisper patch must not overwrite its shared upstream source")
endif()
file(READ "${_veycad_input}" _veycad_source)
string(REPLACE "\r\n" "\n" _veycad_source "${_veycad_source}")
string(SHA256 _veycad_source_sha "${_veycad_source}")
if(NOT _veycad_source_sha STREQUAL "a884ff3eb3bbf7558958c4d06a299cd8d97d87a7d5c7aac9138b8ff6a2ef04d7")
    message(FATAL_ERROR "Whisper source differs from pinned 48f628a audio-bounds patch input")
endif()
function(_veycad_replace expected before after)
    string(REPLACE "${before}" "" _veycad_without "${_veycad_source}")
    string(LENGTH "${_veycad_source}" _veycad_before_length)
    string(LENGTH "${_veycad_without}" _veycad_after_length)
    string(LENGTH "${before}" _veycad_anchor_length)
    math(EXPR _veycad_matches "(${_veycad_before_length} - ${_veycad_after_length}) / ${_veycad_anchor_length}")
    if(NOT _veycad_matches EQUAL expected)
        message(FATAL_ERROR "Whisper audio-bounds patch anchor count differs")
    endif()
    string(REPLACE "${before}" "${after}" _veycad_replaced "${_veycad_source}")
    set(_veycad_source "${_veycad_replaced}" PARENT_SCOPE)
endfunction()
_veycad_replace(1
    "float   temperature) {"
    "float   temperature, int timestamp_ticks_remaining) {")
_veycad_replace(1
    "whisper_process_logits(*ctx, *state, state->decoders[0], params, t_cur);"
    "whisper_process_logits(*ctx, *state, state->decoders[0], params, t_cur, seek_end - seek);")
_veycad_replace(1
    "whisper_process_logits(*ctx, *state, decoder, params, t_cur);"
    "whisper_process_logits(*ctx, *state, decoder, params, t_cur, seek_end - seek);")
_veycad_replace(1
    "\n        // populate the logprobs array (log_softmax)"
    "\n        // Veycad: padding is encoder context, not an available output timestamp.\n        // Run after all logit callbacks/filters and before normalization/sampling.\n        const int timestamp_end = vocab.token_beg + std::min(100*WHISPER_CHUNK_SIZE, timestamp_ticks_remaining) / 2;\n        for (int i = timestamp_end + 1; i < n_logits; ++i) {\n            logits[i] = -INFINITY;\n        }\n\n        // populate the logprobs array (log_softmax)")
_veycad_replace(3
    "seek_delta = 100*WHISPER_CHUNK_SIZE;"
    "seek_delta = std::min(100*WHISPER_CHUNK_SIZE, seek_end - seek);")
# Avoid rewriting identical output on a repeated configure; never touch upstream.
set(_veycad_existing "")
if(EXISTS "${_veycad_output}")
    file(READ "${_veycad_output}" _veycad_existing)
endif()
if(NOT _veycad_existing STREQUAL _veycad_source)
    # Replace the directory entry, so even an existing hardlink is detached safely.
    string(RANDOM LENGTH 16 ALPHABET 0123456789abcdef _veycad_suffix)
    set(_veycad_temp "${_veycad_output}.tmp-${_veycad_suffix}")
    if(EXISTS "${_veycad_temp}" OR IS_SYMLINK "${_veycad_temp}")
        message(FATAL_ERROR "Whisper patch temporary output already exists")
    endif()
    file(WRITE "${_veycad_temp}" "${_veycad_source}")
    file(RENAME "${_veycad_temp}" "${_veycad_output}" RESULT _veycad_rename)
    if(NOT _veycad_rename STREQUAL "0")
        file(REMOVE "${_veycad_temp}")
        message(FATAL_ERROR "Whisper private output replacement failed: ${_veycad_rename}")
    endif()
endif()
