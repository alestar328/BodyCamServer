#!/usr/bin/env python3

"""
===============================================================================
MP4 / H.264 TEST FILE GENERATOR
===============================================================================

Purpose:
--------
This script creates sample MP4 video files for testing.

The generated MP4 consists of:

    1. A user-supplied JPEG image
       - The JPEG is displayed continuously as the video picture.
       - FFmpeg converts the still image into an H.264 video stream.

    2. A user-supplied MP3 audio file
       - The MP3 audio is looped continuously.
       - The audio continues until the requested video duration is reached.

    3. An H.264 encoded video stream
       - The user can choose the video bitrate.
       - The user can choose resolution and frame rate.

    4. A selectable audio codec
       - AAC
       - MP3

    5. A selectable audio bitrate.

    6. A user-defined output duration.

Typical use cases:
------------------
This is useful when testing:

    - Video encryption
    - MP4 file processing
    - File hashing
    - AES encryption/decryption
    - Upload/download systems
    - Storage calculations
    - Video ingestion systems
    - Streaming systems
    - Metadata extraction
    - Mobile-device performance
    - Large-file handling
    - Network transfer testing

Requirements:
-------------

Python:
    Python 3.x

FFmpeg:
    FFmpeg must be installed and available in PATH.

Linux / Kali:
    sudo apt install ffmpeg

Termux / Android:
    pkg install ffmpeg
    pkg install python

Check installation:

    ffmpeg -version

Example:
--------

    python3 create_test_mp4.py

===============================================================================
"""

import os
import sys
import shutil
import subprocess


# =============================================================================
# VIDEO BITRATE OPTIONS
# =============================================================================
#
# These bitrates roughly cover common H.264 testing scenarios.
#
# NOTE:
# Actual final file size will NOT exactly equal:
#
#       bitrate × duration
#
# because:
#
#   - Audio consumes additional bandwidth.
#   - MP4 containers introduce small overhead.
#   - Encoding behavior can vary.
#
# However, when using a fixed bitrate target, this gives reasonably predictable
# test file sizes.
#
# Values are expressed in FFmpeg bitrate syntax:
#
#       2M = 2 megabits per second
#       4M = 4 megabits per second
#

VIDEO_BITRATES = {
    "1": ("2 Mbps", "2M"),
    "2": ("4 Mbps", "4M"),
    "3": ("6 Mbps", "6M"),
    "4": ("8 Mbps", "8M"),
    "5": ("10 Mbps", "10M"),
    "6": ("12 Mbps", "12M"),
    "7": ("15 Mbps", "15M"),
    "8": ("20 Mbps", "20M"),
}


# =============================================================================
# AUDIO BITRATE OPTIONS
# =============================================================================

AUDIO_BITRATES = {
    "1": ("64 kbps", "64k"),
    "2": ("96 kbps", "96k"),
    "3": ("128 kbps", "128k"),
    "4": ("160 kbps", "160k"),
    "5": ("192 kbps", "192k"),
    "6": ("256 kbps", "256k"),
    "7": ("320 kbps", "320k"),
}


# =============================================================================
# RESOLUTION OPTIONS
# =============================================================================
#
# The image will be scaled to fit inside the selected video resolution.
#
# The aspect ratio of the original JPEG is preserved.
#
# Any unused space is padded with black.
#
# This avoids distorting/stretching the supplied JPEG.
#

RESOLUTIONS = {
    "1": ("720p HD", "1280x720"),
    "2": ("1080p Full HD", "1920x1080"),
    "3": ("1440p QHD", "2560x1440"),
    "4": ("2160p 4K", "3840x2160"),
}


# =============================================================================
# FRAME RATE OPTIONS
# =============================================================================
#
# Because the source is only a still JPEG, there is usually no reason to use
# extremely high frame rates.
#
# 24, 25 and 30 FPS are perfectly adequate for most testing.
#

FRAME_RATES = {
    "1": ("24 FPS", "24"),
    "2": ("25 FPS", "25"),
    "3": ("30 FPS", "30"),
    "4": ("50 FPS", "50"),
    "5": ("60 FPS", "60"),
}


# =============================================================================
# AUDIO CODEC OPTIONS
# =============================================================================
#
# AAC is the normal choice for MP4.
#
# AAC + H.264 is one of the most widely supported MP4 combinations.
#
# MP3 inside MP4 is possible, but AAC is generally preferred when creating
# realistic MP4 test samples.
#

AUDIO_CODECS = {
    "1": ("AAC", "aac"),
    "2": ("MP3", "libmp3lame"),
}


# =============================================================================
# HELPER FUNCTION
# =============================================================================

def print_separator():
    """
    Print a visual separator to make the interactive interface easier to read.
    """

    print("=" * 78)


# =============================================================================
# CHECK FFMPEG
# =============================================================================

def check_ffmpeg():
    """
    Verify that the FFmpeg executable exists.

    shutil.which() searches the current PATH for the executable.

    If FFmpeg cannot be located, there is no point continuing because the
    Python script itself does not perform video encoding. Python simply builds
    and executes the FFmpeg command.
    """

    ffmpeg_path = shutil.which("ffmpeg")

    if ffmpeg_path is None:

        print("\nERROR: FFmpeg was not found.\n")

        print("Install FFmpeg first.")

        print("\nKali / Debian / Ubuntu:")
        print("    sudo apt install ffmpeg")

        print("\nAndroid / Termux:")
        print("    pkg install ffmpeg")

        sys.exit(1)

    return ffmpeg_path


# =============================================================================
# GET EXISTING FILE
# =============================================================================

def get_existing_file(prompt, extensions=None):
    """
    Ask the user for a file path and verify that the file exists.

    Parameters:
    -----------

    prompt:
        Prompt displayed to the user.

    extensions:
        Optional list/tuple containing allowed file extensions.

    Example:

        extensions=(".jpg", ".jpeg")

    The function continues asking until the user provides a valid file.
    """

    while True:

        filename = input(prompt).strip()

        # Remove accidental quotation marks.
        #
        # This is particularly useful when users copy file paths from a file
        # manager or terminal where paths may be surrounded by quotes.

        filename = filename.strip('"').strip("'")

        # Expand Linux-style home paths.
        #
        # Example:
        #
        #       ~/Pictures/test.jpg
        #
        # becomes:
        #
        #       /home/user/Pictures/test.jpg

        filename = os.path.expanduser(filename)

        if not os.path.isfile(filename):

            print("\nFile does not exist:")
            print(f"    {filename}\n")

            continue

        if extensions:

            if not filename.lower().endswith(extensions):

                print("\nUnexpected file extension.")

                print(
                    "Expected one of: "
                    + ", ".join(extensions)
                )

                print()

                continue

        return filename


# =============================================================================
# GENERIC MENU
# =============================================================================

def choose_from_menu(title, options):
    """
    Display a dictionary-based menu and return the selected value.

    Each dictionary entry contains:

        menu number : (description, ffmpeg value)

    Example:

        "1": ("2 Mbps", "2M")

    The function returns:

        ("2 Mbps", "2M")
    """

    while True:

        print_separator()

        print(title)

        print_separator()

        for key, value in options.items():

            description = value[0]

            print(f"{key}. {description}")

        choice = input("\nSelect option: ").strip()

        if choice in options:

            return options[choice]

        print("\nInvalid selection. Please try again.\n")


# =============================================================================
# GET DURATION
# =============================================================================

def get_duration():
    """
    Ask the user how long the generated MP4 should be.

    The user can enter the duration in:

        seconds
        minutes
        hours

    The function converts everything internally into seconds.
    """

    print_separator()

    print("VIDEO DURATION")

    print_separator()

    print("1. Seconds")
    print("2. Minutes")
    print("3. Hours")

    while True:

        unit = input("\nSelect duration unit: ").strip()

        if unit in ("1", "2", "3"):
            break

        print("Invalid option.")

    while True:

        try:

            value = float(
                input("Enter duration: ").strip()
            )

            if value <= 0:

                print("Duration must be greater than zero.")

                continue

            break

        except ValueError:

            print("Please enter a valid numeric value.")

    # Convert the selected unit into seconds.

    if unit == "1":

        seconds = value

    elif unit == "2":

        seconds = value * 60

    else:

        seconds = value * 3600

    return seconds


# =============================================================================
# ESTIMATE FILE SIZE
# =============================================================================

def estimate_file_size(video_bitrate, audio_bitrate, duration_seconds):
    """
    Estimate the resulting MP4 file size.

    Bitrates are supplied using FFmpeg syntax:

        2M
        10M
        128k
        320k

    We convert both values into bits per second.

    Formula:

        total_bits =
            (video_bitrate + audio_bitrate) * duration

        bytes =
            total_bits / 8

    This is only an estimate.

    Real MP4 files may differ slightly because of:

        - MP4 container overhead
        - encoder behavior
        - muxing overhead
        - bitrate variability
    """

    def bitrate_to_bps(value):

        value = value.lower()

        if value.endswith("m"):

            return float(value[:-1]) * 1_000_000

        if value.endswith("k"):

            return float(value[:-1]) * 1_000

        return float(value)

    video_bps = bitrate_to_bps(video_bitrate)

    audio_bps = bitrate_to_bps(audio_bitrate)

    total_bps = video_bps + audio_bps

    total_bits = total_bps * duration_seconds

    total_bytes = total_bits / 8

    return total_bytes


# =============================================================================
# FORMAT FILE SIZE
# =============================================================================

def format_size(size_bytes):
    """
    Convert a byte count into a human-readable representation.
    """

    units = [
        "B",
        "KB",
        "MB",
        "GB",
        "TB"
    ]

    size = float(size_bytes)

    for unit in units:

        if size < 1024:

            return f"{size:.2f} {unit}"

        size /= 1024

    return f"{size:.2f} PB"


# =============================================================================
# FORMAT TIME
# =============================================================================

def format_duration(seconds):
    """
    Convert seconds into HH:MM:SS representation.
    """

    seconds = int(seconds)

    hours = seconds // 3600

    minutes = (seconds % 3600) // 60

    remaining_seconds = seconds % 60

    return (
        f"{hours:02d}:"
        f"{minutes:02d}:"
        f"{remaining_seconds:02d}"
    )


# =============================================================================
# MAIN PROGRAM
# =============================================================================

def main():

    print()
    print_separator()

    print("H.264 MP4 TEST FILE GENERATOR")

    print_separator()

    print(
        """
This program creates an MP4 test video using:

    JPEG image  -> H.264 video
    MP3 file    -> looped audio

The image and audio will continue for the entire selected video duration.
"""
    )

    # -------------------------------------------------------------------------
    # Verify that FFmpeg exists.
    # -------------------------------------------------------------------------

    ffmpeg = check_ffmpeg()

    print(f"FFmpeg detected: {ffmpeg}\n")

    # -------------------------------------------------------------------------
    # JPEG INPUT
    # -------------------------------------------------------------------------

    jpeg_file = get_existing_file(
        "Enter JPEG image path: ",
        (".jpg", ".jpeg")
    )

    # -------------------------------------------------------------------------
    # MP3 INPUT
    # -------------------------------------------------------------------------

    mp3_file = get_existing_file(
        "Enter MP3 audio path: ",
        (".mp3",)
    )

    # -------------------------------------------------------------------------
    # VIDEO BITRATE
    # -------------------------------------------------------------------------

    video_bitrate_name, video_bitrate = choose_from_menu(
        "SELECT H.264 VIDEO BITRATE",
        VIDEO_BITRATES
    )

    # -------------------------------------------------------------------------
    # VIDEO RESOLUTION
    # -------------------------------------------------------------------------

    resolution_name, resolution = choose_from_menu(
        "SELECT VIDEO RESOLUTION",
        RESOLUTIONS
    )

    # -------------------------------------------------------------------------
    # FRAME RATE
    # -------------------------------------------------------------------------

    frame_rate_name, frame_rate = choose_from_menu(
        "SELECT FRAME RATE",
        FRAME_RATES
    )

    # -------------------------------------------------------------------------
    # AUDIO CODEC
    # -------------------------------------------------------------------------

    audio_codec_name, audio_codec = choose_from_menu(
        "SELECT AUDIO CODEC",
        AUDIO_CODECS
    )

    # -------------------------------------------------------------------------
    # AUDIO BITRATE
    # -------------------------------------------------------------------------

    audio_bitrate_name, audio_bitrate = choose_from_menu(
        "SELECT AUDIO BITRATE",
        AUDIO_BITRATES
    )

    # -------------------------------------------------------------------------
    # VIDEO DURATION
    # -------------------------------------------------------------------------

    duration = get_duration()

    # -------------------------------------------------------------------------
    # OUTPUT FILENAME
    # -------------------------------------------------------------------------

    print_separator()

    output_file = input(
        "Output MP4 filename [test_video.mp4]: "
    ).strip()

    if not output_file:

        output_file = "test_video.mp4"

    if not output_file.lower().endswith(".mp4"):

        output_file += ".mp4"

    # -------------------------------------------------------------------------
    # CALCULATE EXPECTED FILE SIZE
    # -------------------------------------------------------------------------

    estimated_size = estimate_file_size(
        video_bitrate,
        audio_bitrate,
        duration
    )

    # -------------------------------------------------------------------------
    # DISPLAY CONFIGURATION
    # -------------------------------------------------------------------------

    print()

    print_separator()

    print("GENERATING TEST MP4")

    print_separator()

    print(f"JPEG source       : {jpeg_file}")
    print(f"Audio source      : {mp3_file}")

    print()

    print(f"Video codec       : H.264 / libx264")
    print(f"Video bitrate     : {video_bitrate_name}")
    print(f"Resolution        : {resolution_name} ({resolution})")
    print(f"Frame rate        : {frame_rate_name}")

    print()

    print(f"Audio codec       : {audio_codec_name}")
    print(f"Audio bitrate     : {audio_bitrate_name}")

    print()

    print(
        f"Duration          : "
        f"{format_duration(duration)} "
        f"({duration:.2f} seconds)"
    )

    print(
        f"Estimated size    : "
        f"{format_size(estimated_size)}"
    )

    print(f"Output file       : {output_file}")

    print_separator()

    # -------------------------------------------------------------------------
    # DETERMINE OUTPUT WIDTH AND HEIGHT
    # -------------------------------------------------------------------------

    width, height = resolution.split("x")

    # -------------------------------------------------------------------------
    # VIDEO FILTER
    # -------------------------------------------------------------------------
    #
    # The filter chain performs two operations:
    #
    # 1. SCALE
    #
    #    Scale the JPEG so that it fits inside the requested resolution while
    #    preserving the source aspect ratio.
    #
    #       force_original_aspect_ratio=decrease
    #
    #    prevents the image from being stretched.
    #
    #
    # 2. PAD
    #
    #    If the source image aspect ratio does not exactly match the video
    #    resolution, black borders are added.
    #
    #    Example:
    #
    #       source JPEG = square
    #       output       = 1920x1080
    #
    #    The image remains square and is centered with padding.
    #
    #
    # 3. FORMAT
    #
    #    yuv420p provides broad compatibility with:
    #
    #       Android
    #       Windows
    #       macOS
    #       browsers
    #       hardware video decoders
    #
    # -------------------------------------------------------------------------

    video_filter = (
        f"scale={width}:{height}:"
        f"force_original_aspect_ratio=decrease,"
        f"pad={width}:{height}:"
        f"(ow-iw)/2:(oh-ih)/2,"
        f"format=yuv420p"
    )

    # -------------------------------------------------------------------------
    # FFMPEG COMMAND
    # -------------------------------------------------------------------------
    #
    # -loop 1
    #
    #       Continuously loop the JPEG image.
    #
    #
    # -i jpeg_file
    #
    #       First input = JPEG.
    #
    #
    # -stream_loop -1
    #
    #       Loop the audio indefinitely.
    #
    #       -1 means infinite loop.
    #
    #
    # -i mp3_file
    #
    #       Second input = MP3.
    #
    #
    # -c:v libx264
    #
    #       Encode video using H.264.
    #
    #
    # -b:v
    #
    #       Requested video bitrate.
    #
    #
    # -minrate / -maxrate
    #
    #       Keeps bitrate close to the selected bitrate.
    #
    #
    # -bufsize
    #
    #       Rate-control buffer.
    #
    #
    # -r
    #
    #       Output frame rate.
    #
    #
    # -c:a
    #
    #       Selected audio codec.
    #
    #
    # -b:a
    #
    #       Audio bitrate.
    #
    #
    # -t
    #
    #       Exact requested output duration.
    #
    #
    # -movflags +faststart
    #
    #       Moves MP4 metadata ("moov atom") toward the beginning of the file.
    #
    #       This is normally useful for streaming/web playback.
    #
    #
    # -y
    #
    #       Overwrite an existing output file without asking FFmpeg for
    #       confirmation.
    #
    # -------------------------------------------------------------------------

    command = [

        ffmpeg,

        # ---------------------------------------------------------------------
        # VIDEO INPUT
        # ---------------------------------------------------------------------

        "-loop",
        "1",

        "-framerate",
        frame_rate,

        "-i",
        jpeg_file,

        # ---------------------------------------------------------------------
        # AUDIO INPUT
        # ---------------------------------------------------------------------

        "-stream_loop",
        "-1",

        "-i",
        mp3_file,

        # ---------------------------------------------------------------------
        # VIDEO ENCODING
        # ---------------------------------------------------------------------

        "-c:v",
        "libx264",

        "-b:v",
        video_bitrate,

        "-minrate",
        video_bitrate,

        "-maxrate",
        video_bitrate,

        "-bufsize",
        video_bitrate,

        "-r",
        frame_rate,

        "-vf",
        video_filter,

        "-pix_fmt",
        "yuv420p",

        # ---------------------------------------------------------------------
        # AUDIO ENCODING
        # ---------------------------------------------------------------------

        "-c:a",
        audio_codec,

        "-b:a",
        audio_bitrate,

        # ---------------------------------------------------------------------
        # OUTPUT DURATION
        # ---------------------------------------------------------------------

        "-t",
        str(duration),

        # ---------------------------------------------------------------------
        # MP4 OPTIONS
        # ---------------------------------------------------------------------

        "-movflags",
        "+faststart",

        # ---------------------------------------------------------------------
        # OVERWRITE EXISTING FILE
        # ---------------------------------------------------------------------

        "-y",

        output_file
    ]

    # -------------------------------------------------------------------------
    # DISPLAY FFMPEG COMMAND
    # -------------------------------------------------------------------------
    #
    # This is extremely useful for testing because the user can see precisely
    # what FFmpeg command the Python wrapper generated.
    # -------------------------------------------------------------------------

    print("\nFFmpeg command:\n")

    print(
        " ".join(
            f'"{arg}"' if " " in str(arg) else str(arg)
            for arg in command
        )
    )

    print()

    print_separator()

    print("FFmpeg encoding started...")

    print_separator()

    # -------------------------------------------------------------------------
    # RUN FFMPEG
    # -------------------------------------------------------------------------

    try:

        subprocess.run(
            command,
            check=True
        )

    except subprocess.CalledProcessError as error:

        print()

        print_separator()

        print("ERROR")

        print_separator()

        print(
            "FFmpeg failed while generating the MP4."
        )

        print(f"Return code: {error.returncode}")

        sys.exit(1)

    except KeyboardInterrupt:

        print("\n\nEncoding cancelled by user.")

        sys.exit(130)

    # -------------------------------------------------------------------------
    # GET FINAL FILE SIZE
    # -------------------------------------------------------------------------

    actual_size = os.path.getsize(output_file)

    # -------------------------------------------------------------------------
    # COMPLETION REPORT
    # -------------------------------------------------------------------------

    print()

    print_separator()

    print("MP4 GENERATION COMPLETE")

    print_separator()

    print(f"Output file       : {output_file}")

    print(
        f"Actual file size  : "
        f"{format_size(actual_size)}"
    )

    print(
        f"Estimated size    : "
        f"{format_size(estimated_size)}"
    )

    difference = actual_size - estimated_size

    percentage_difference = (
        difference / estimated_size
    ) * 100

    print(
        f"Difference        : "
        f"{format_size(abs(difference))} "
        f"({percentage_difference:+.2f}%)"
    )

    print()

    print("Video:")
    print("    Container     : MP4")
    print("    Codec         : H.264 / AVC")
    print(f"    Bitrate       : {video_bitrate_name}")
    print(f"    Resolution    : {resolution}")
    print(f"    Frame rate    : {frame_rate} FPS")

    print()

    print("Audio:")
    print(f"    Codec         : {audio_codec_name}")
    print(f"    Bitrate       : {audio_bitrate_name}")

    print()

    print(
        f"Duration          : "
        f"{format_duration(duration)}"
    )

    print_separator()


# =============================================================================
# PROGRAM ENTRY POINT
# =============================================================================
#
# This prevents main() from executing if this Python file is imported as a
# module by another Python program.
#
# When executed directly:
#
#       python3 create_test_mp4.py
#
# __name__ equals "__main__" and main() runs.
#
# =============================================================================

if __name__ == "__main__":

    main()