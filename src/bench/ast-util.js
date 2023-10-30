function objectAstToData(node) {
  if (Array.isArray(node)) {
      return node.map(objectAstToData);
  } else if (node && typeof node === 'object') {
      const result = {};
      for (const key of Object.keys(node)) {
          result[key] = objectAstToData(node[key]);
      }
      return result;
  } else {
      return node;
  }
}

exports.objectAstToData = objectAstToData;