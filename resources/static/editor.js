// Grumpy Editor - Pure JS with Preact
// Import Preact from UNPKG
const { h, render, Component, createRef } = window.preact;
const { useState, useEffect, useRef } = window.preactHooks;
const { signal, computed, effect } = window.preactSignalsCore;

// ============================================================================
// State management with Preact Signals
// ============================================================================

const postSignal = signal(null);
const mediaStatusSignal = signal(null);
const mediaDragSignal = signal(null);
const statusSignal = signal(null);

// ============================================================================
// Utilities
// ============================================================================

function avatarUrl(author) {
  const knownAuthors = ['nikitonsky', 'dmitriid', 'freetonik', 'igrishaev'];
  return knownAuthors.includes(author)
    ? `/static/${author}.jpg`
    : '/static/guest.jpg';
}

function fit(x, y, maxX, maxY) {
  if (x > maxX) {
    return fit(maxX, y * (maxX / x), maxX, maxY);
  }
  if (y > maxY) {
    return fit(x * (maxY / y), maxY, maxX, maxY);
  }
  return [Math.floor(x), Math.floor(y)];
}

function isVideo(contentType) {
  return contentType && contentType.startsWith('video/');
}

// ============================================================================
// Fetch utilities
// ============================================================================

function fetchRequest(method, url, opts = {}) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();

    xhr.addEventListener('load', function() {
      if (this.status !== 200) {
        console.warn('Error fetching', url, ':', this.responseText);
        reject(this.responseText);
      } else {
        resolve(this.responseText);
      }
    });

    xhr.addEventListener('error', () => {
      reject('Network error');
    });

    if (opts.progress) {
      xhr.upload.addEventListener('progress', (e) => {
        if (e.lengthComputable) {
          opts.progress(e.loaded / e.total);
        }
      });
    }

    xhr.open(method, url);

    if (opts.body instanceof File) {
      xhr.send(opts.body);
    } else if (opts.body) {
      xhr.setRequestHeader('Content-Type', 'application/json');
      xhr.send(JSON.stringify(opts.body));
    } else {
      xhr.send();
    }
  });
}

// ============================================================================
// Avatar Component
// ============================================================================

function Avatar() {
  const author = postSignal.value?.['post/author'] || 'guest';

  return h('img', {
    class: 'post_avatar',
    src: avatarUrl(author)
  });
}

// ============================================================================
// Body/Textarea Component
// ============================================================================

function TextareaComponent() {
  const disabled = statusSignal.value?.status != null;

  const onChange = (e) => {
    postSignal.value = {
      ...postSignal.value,
      'post/body': e.target.value
    };
  };

  return h('div', { class: 'textarea' },
    h('div', { class: disabled ? 'input disabled' : 'input' },
      h('textarea', {
        disabled: disabled,
        placeholder: 'Be grumpy here...',
        value: postSignal.value?.['post/body'] || '',
        onInput: onChange
      })
    )
  );
}

// ============================================================================
// Media Components
// ============================================================================

function MediaElement({ src, contentType, dimensions }) {
  if (!src) return null;

  let style = {};
  if (dimensions && dimensions.length === 2) {
    const [w, h] = dimensions;
    const [w2, h2] = fit(w, h, 550, 500);
    style = { width: w2 + 'px', height: h2 + 'px' };
  }

  if (isVideo(contentType)) {
    return h('video', {
      autoPlay: true,
      muted: true,
      loop: true,
      preload: 'auto',
      playsInline: true,
      style: style
    }, h('source', { type: contentType, src: src }));
  } else {
    return h('img', { src: src, style: style });
  }
}

function MediaDelete() {
  if (mediaDragSignal.value?.dragging || statusSignal.value?.status) {
    return null;
  }

  const onClick = () => {
    const newPost = { ...postSignal.value };
    delete newPost['post/media'];
    delete newPost['post/media-full'];
    postSignal.value = newPost;
  };

  return h('div', {
    class: 'media-delete cursor-pointer',
    onClick: onClick
  });
}

function MediaOverlay() {
  const progress = mediaStatusSignal.value?.progress;

  if (progress != null) {
    const percent = progress * 100;
    return h('div', {
      class: 'upload-overlay',
      style: { height: (100 - percent) + '%' }
    });
  }

  if (mediaStatusSignal.value?.error) {
    return h('div', { class: 'failed-overlay' });
  }

  return null;
}

function MediaStatus() {
  if (mediaStatusSignal.value?.error) {
    return h('div', { class: 'status error stick-left stick-bottom' }, mediaStatusSignal.value.error);
  }

  if (mediaStatusSignal.value?.message) {
    return h('div', { class: 'status stick-left stick-bottom' }, mediaStatusSignal.value.message);
  }

  return null;
}

function uploadMedia(file) {
  const objectUrl = URL.createObjectURL(file);
  const videoEl = isVideo(file.type);
  const media = document.createElement(videoEl ? 'video' : 'img');

  const onLoad = () => {
    const dimensions = videoEl
      ? [media.videoWidth, media.videoHeight]
      : [media.naturalWidth, media.naturalHeight];

    mediaStatusSignal.value = null;
    mediaDragSignal.value = null;

    // Start upload
    const currentObjectUrl = objectUrl;

    const isRelevant = () => {
      const post = postSignal.value;
      const mediaStatus = mediaStatusSignal.value;
      return post?.['post/media']?.['media/object-url'] === currentObjectUrl
        && mediaStatus?.progress != null;
    };

    const oldObjectUrl = postSignal.value?.['post/media']?.['media/object-url'];
    if (oldObjectUrl) {
      URL.revokeObjectURL(oldObjectUrl);
    }

    mediaStatusSignal.value = { progress: 0 };
    postSignal.value = {
      ...postSignal.value,
      'post/media': {
        'media/object-url': objectUrl,
        'media/width': dimensions[0],
        'media/height': dimensions[1],
        'media/content-type': file.type
      }
    };

    fetchRequest('POST', '/media/uploads', {
      body: file,
      progress: (progress) => {
        if (isRelevant()) {
          mediaStatusSignal.value = {
            progress: progress,
            message: progress < 1
              ? `Uploading ${Math.floor(progress * 100)}%...`
              : 'Converting...'
          };
        }
      }
    }).then(payload => {
      if (isRelevant()) {
        mediaStatusSignal.value = null;
        const oldUrl = postSignal.value?.['post/media']?.['media/object-url'];
        if (oldUrl) {
          URL.revokeObjectURL(oldUrl);
        }
        postSignal.value = { ...postSignal.value, ...JSON.parse(payload) };
      }
    }).catch(error => {
      if (isRelevant()) {
        mediaStatusSignal.value = { error: `Upload failed with ${error}` };
      }
    });
  };

  const onError = () => {
    URL.revokeObjectURL(objectUrl);
    mediaStatusSignal.value = { error: 'Unsupported format, we accept jpg/png/gif/mp4' };
    mediaDragSignal.value = null;
  };

  media.addEventListener(videoEl ? 'loadedmetadata' : 'load', onLoad);
  media.addEventListener('error', onError);
  media.src = objectUrl;
}

function FileInput() {
  const inputRef = useRef(null);

  useEffect(() => {
    let dragCounter = 0;

    const onDragEnter = (e) => {
      dragCounter++;
      if (dragCounter === 1) {
        mediaDragSignal.value = { ...mediaDragSignal.value, dragging: true };
      }
    };

    const onDragLeave = (e) => {
      dragCounter--;
      if (dragCounter === 0) {
        if (!mediaDragSignal.value?.dropped) {
          mediaDragSignal.value = { ...mediaDragSignal.value, dragging: false };
        }
      }
    };

    const onDragEnd = () => {
      dragCounter = 0;
      if (!mediaDragSignal.value?.dropped) {
        mediaDragSignal.value = { ...mediaDragSignal.value, dragging: false };
      }
    };

    const onDrop = () => {
      dragCounter = 0;
    };

    document.documentElement.addEventListener('dragenter', onDragEnter);
    document.documentElement.addEventListener('dragleave', onDragLeave);
    document.documentElement.addEventListener('dragend', onDragEnd);
    document.documentElement.addEventListener('drop', onDrop);

    return () => {
      document.documentElement.removeEventListener('dragenter', onDragEnter);
      document.documentElement.removeEventListener('dragleave', onDragLeave);
      document.documentElement.removeEventListener('dragend', onDragEnd);
      document.documentElement.removeEventListener('drop', onDrop);
    };
  }, []);

  const onChange = (e) => {
    const files = e.target.files;
    if (files.length > 0) {
      uploadMedia(files[0]);
    }
  };

  return h('input', {
    ref: inputRef,
    type: 'file',
    class: 'no-display',
    onChange: onChange
  });
}

function NoMedia() {
  const onClick = (e) => {
    if (!statusSignal.value?.status) {
      document.querySelector('input[type=file]').click();
    }
    e.preventDefault();
  };

  return h('div', {
    class: 'upload no-select cursor-pointer',
    onClick: onClick
  },
    h('div', { class: 'corner top-left' }),
    h('div', { class: 'corner top-right' }),
    h('div', { class: 'corner bottom-left' }),
    h('div', { class: 'corner bottom-right' }),
    h('div', { class: 'label' }, 'Drag media here'),
    mediaStatusSignal.value?.error && h('div', { class: 'status stick-left stick-bottom' }, mediaStatusSignal.value.error)
  );
}

function DraggingOverlay() {
  const dropRef = useRef(null);

  useEffect(() => {
    const el = dropRef.current;
    let dragOverCounter = 0;

    const onDragEnter = (e) => {
      e.preventDefault();
      dragOverCounter++;
      if (dragOverCounter === 1) {
        mediaDragSignal.value = { ...mediaDragSignal.value, dragover: true };
      }
    };

    const onDragOver = (e) => {
      e.preventDefault();
    };

    const onDragLeave = (e) => {
      e.preventDefault();
      dragOverCounter--;
      if (dragOverCounter === 0) {
        if (!mediaDragSignal.value?.dropped) {
          mediaDragSignal.value = { ...mediaDragSignal.value, dragover: false };
        }
      }
    };

    const onDrop = (e) => {
      e.preventDefault();
      dragOverCounter = 0;
      mediaDragSignal.value = { ...mediaDragSignal.value, dropped: true };

      const files = e.dataTransfer.files;
      if (files.length > 0) {
        uploadMedia(files[0]);
      }
    };

    el.addEventListener('dragenter', onDragEnter);
    el.addEventListener('dragover', onDragOver);
    el.addEventListener('dragleave', onDragLeave);
    el.addEventListener('drop', onDrop);

    return () => {
      el.removeEventListener('dragenter', onDragEnter);
      el.removeEventListener('dragover', onDragOver);
      el.removeEventListener('dragleave', onDragLeave);
      el.removeEventListener('drop', onDrop);
    };
  }, []);

  return h('div', {
    ref: dropRef,
    class: mediaDragSignal.value?.dragover ? 'dragging dragover' : 'dragging'
  },
    h('div', { class: 'label' }, 'Drop here')
  );
}

function RenderDragging() {
  if (!statusSignal.value?.status && mediaDragSignal.value?.dragging && !mediaStatusSignal.value?.progress) {
    return h(DraggingOverlay);
  }

  return null;
}

function MediaComponent() {
  const media = postSignal.value?.['post/media'];

  return h('div', null,
    h(FileInput),
    media
      ? h('div', { class: 'media' },
          h('div', { class: 'media-wrap' },
            h(MediaElement, {
              src: media['media/object-url'] || `/media/${media['media/url']}`,
              contentType: media['media/content-type'],
              dimensions: [media['media/width'], media['media/height']]
            }),
            h(MediaDelete),
            h(MediaOverlay)
          ),
          h(MediaStatus)
        )
      : h(NoMedia)
  );
}

// ============================================================================
// Buttons Component
// ============================================================================

function isReady() {
  const mediaStatus = mediaStatusSignal.value;
  const status = statusSignal.value;
  const body = postSignal.value?.['post/body'] || '';

  return !mediaStatus?.progress
    && !mediaStatus?.error
    && !status?.status
    && body.trim().length > 0;
}

function publish() {
  statusSignal.value = { status: 'publishing', error: null };

  fetchRequest('POST', window.location.pathname, {
    body: postSignal.value
  }).then(payload => {
    const post = JSON.parse(payload);
    const postId = postSignal.value?.['post/id'];
    const loc = postId ? `/${post['post/id']}` : '/';
    window.location.href = loc;
  }).catch(error => {
    statusSignal.value = {
      status: null,
      error: `Publishing failed with ${error}`
    };
  });
}

function PostButton() {
  if (statusSignal.value?.status) {
    return h('div', { class: 'post-post-loader row center middle' },
      h('div', { class: 'loader loading' })
    );
  }

  return h('button', {
    class: 'post-post row',
    disabled: !isReady(),
    onClick: publish
  },
    h('img', { class: 'button', src: '/static/editor/post_button.svg' }),
    h('img', { class: 'hand', src: '/static/editor/post_hand.svg' }),
    h('div', { class: 'label' }, 'POST')
  );
}

function UpdateButton() {
  if (statusSignal.value?.status) {
    return h('div', { class: 'post-btn-loader' },
      h('div', { class: 'loader small loading' }),
      'Update'
    );
  }

  return h('button', {
    class: 'btn post-update',
    disabled: !isReady(),
    onClick: publish
  }, 'Update');
}

function DeleteButton() {
  const postId = postSignal.value?.['post/id'];

  return h('a', {
    href: `/${postId}/delete`,
    style: { color: '#c33' }
  }, '[ Delete ]');
}

function ButtonsComponent() {
  const isEdit = postSignal.value?.['post/id'] != null;

  return h('div', { class: 'column' },
    statusSignal.value?.error && h('div', { class: 'status', style: { zIndex: 1 } }, statusSignal.value.error),
    isEdit
      ? h('div', { class: 'row middle space-between' },
          h(DeleteButton),
          h(UpdateButton)
        )
      : h('div', { class: 'row right' }, h(PostButton))
  );
}

// ============================================================================
// Main Editor Component
// ============================================================================

function Editor() {
  return h('div', { class: 'editor relative row' },
    h(Avatar),
    h('div', { class: 'column grow' },
      h(MediaComponent),
      h(TextareaComponent),
      h(ButtonsComponent)
    ),
    h(RenderDragging)
  );
}

// ============================================================================
// App initialization
// ============================================================================

function refresh() {
  const mount = document.querySelector('.mount');
  if (!mount) {
    console.error('Mount point not found');
    return;
  }

  if (!postSignal.value) {
    const data = mount.getAttribute('data');
    if (data) {
      try {
        const post = JSON.parse(data);
        postSignal.value = post;
      } catch (e) {
        console.error('Failed to parse post data:', e);
        postSignal.value = { 'post/author': 'guest', 'post/body': '' };
      }
    }
  }

  render(h(Editor), mount);
}
