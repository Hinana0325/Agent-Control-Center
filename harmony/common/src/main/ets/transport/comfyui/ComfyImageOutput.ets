// ComfyImageOutput（对标 android transport/comfyui/ComfyImageOutput.kt）
// ComfyUI 图片输出信息（filename + subfolder + type），
// 通过 /view 端点下载；从 /history/{prompt_id} 响应中提取。
//
// history 响应格式：
// {
//   "<prompt_id>": {
//     "outputs": {
//       "9": {  // SaveImage 节点 ID
//         "images": [
//           { "filename": "ComfyUI_00001_.png", "subfolder": "", "type": "output" }
//         ]
//       }
//     }
//   }
// }

export interface ComfyImageOutput {
  filename: string;
  subfolder: string;
  type: string;
}

export class ComfyImageOutputExtractor {
  /** 从 history 的 promptEntry 中提取所有图片输出。 */
  static extract(promptEntry: Record<string, Object>): ComfyImageOutput[] {
    const outputsValue: Object | undefined = promptEntry['outputs'];
    if (outputsValue === undefined || outputsValue === null) {
      return [];
    }
    const outputs: Record<string, Object> = outputsValue as Record<string, Object>;

    const images: ComfyImageOutput[] = [];
    const nodeIds: string[] = Object.keys(outputs);
    for (const nodeId of nodeIds) {
      const nodeOutput: Object = outputs[nodeId];
      const outputObj: Record<string, Object> = nodeOutput as Record<string, Object>;
      const imagesValue: Object | undefined = outputObj['images'];
      if (imagesValue === undefined || imagesValue === null) {
        continue;
      }
      const imagesArray: Object[] = imagesValue as Object[];
      for (const imgElement of imagesArray) {
        const img: Record<string, Object> = imgElement as Record<string, Object>;
        const filenameValue: Object | undefined = img['filename'];
        if (filenameValue === undefined || filenameValue === null) {
          continue;
        }
        const subfolderValue: Object | undefined = img['subfolder'];
        const typeValue: Object | undefined = img['type'];
        images.push({
          filename: filenameValue as string,
          subfolder: subfolderValue !== undefined && subfolderValue !== null
            ? subfolderValue as string : '',
          type: typeValue !== undefined && typeValue !== null
            ? typeValue as string : 'output'
        });
      }
    }
    return images;
  }
}
