# Override FindOpenCL.cmake to use our submodules

if(TARGET OpenCL)
    set(OpenCL_FOUND TRUE)
    set(OpenCL_LIBRARIES OpenCL)
    # The ICD Loader adds the headers to the target interface, so this might be redundant but safe
    get_target_property(OpenCL_INCLUDE_DIRS OpenCL INTERFACE_INCLUDE_DIRECTORIES)
    if(NOT OpenCL_INCLUDE_DIRS)
         set(OpenCL_INCLUDE_DIRS ${CMAKE_CURRENT_LIST_DIR}/../../../../external/OpenCL-Headers)
    endif()
    set(OpenCL_VERSION_STRING "3.0")
    
    if(NOT TARGET OpenCL::OpenCL)
        add_library(OpenCL::OpenCL ALIAS OpenCL)
    endif()
    
    return()
endif()

# Fallback if target not yet defined (should not happen if order is correct)
set(OpenCL_FOUND TRUE)
set(OpenCL_LIBRARIES OpenCL)
set(OpenCL_INCLUDE_DIRS ${CMAKE_CURRENT_LIST_DIR}/../../../../external/OpenCL-Headers)
set(OpenCL_VERSION_STRING "3.0")
