package marumasa.marumasa_sign.core.gltf.draco;
import java.io.Serializable;
import java.util.ArrayList;
class MeshPredictionSchemeConstrainedMultiParallelogram extends MeshPredictionScheme
{    
    static final int K_MAX_NUM_PARALLELOGRAMS = 4;
    // Crease edges are used to store whether any given edge should be used for
    // parallelogram prediction or not. New values are added in the order in which
    // the edges are processed. For better compression, the flags are stored in
    // in separate contexts based on the number of available parallelograms at a
    // given vertex.
    // 
    ArrayList<Boolean>[] is_crease_edge_;
    public MeshPredictionSchemeConstrainedMultiParallelogram(PointAttribute attribute, PredictionSchemeTransform transform, MeshPredictionSchemeData meshData)
    {
        super(attribute, transform, meshData);
        this.$initFields$();
        for (int i = 0; i < K_MAX_NUM_PARALLELOGRAMS; ++i)
        {
            is_crease_edge_[i] = new ArrayList<Boolean>();
        }
        
    }
    
    public int getPredictionMethod()
    {
        return PredictionSchemeMethod.CONSTRAINED_MULTI_PARALLELOGRAM;
    }
    
        @Override
    public void computeCorrectionValues(IntSpan in_data, IntSpan out_corr, int size, int num_components, int[] entry_to_point_id_map) {
        throw new UnsupportedOperationException();
    }
    @Override
    public void computeOriginalValues(IntSpan in_corr, IntSpan out_data, int size, int num_components, int[] entry_to_point_id_map)
        throws DrakoException
    {
        this.transform_.initializeDecoding(num_components);
        int[][] pred_vals = new int[K_MAX_NUM_PARALLELOGRAMS][];
        for (int i = 0; i < K_MAX_NUM_PARALLELOGRAMS; ++i)
        {
            pred_vals[i] = new int[num_components];
        }
        
        this.transform_.computeOriginalValue(IntSpan.wrap(pred_vals[0]), in_corr, out_data);
        ICornerTable table = this.meshData.getCornerTable();
        int[] vertex_to_data_map = this.meshData.vertexToDataMap;
        int[] is_crease_edge_pos = new int[K_MAX_NUM_PARALLELOGRAMS];
        int[] multi_pred_vals = new int[num_components];
        int corner_map_size = this.meshData.dataToCornerMap.getCount();
        for (int p = 1; p < corner_map_size; ++p)
        {
            int start_corner_id = this.meshData.dataToCornerMap.get(p);
            int corner_id = start_corner_id;
            int num_parallelograms = 0;
            boolean first_pass = true;
            while (corner_id != CornerTable.K_INVALID_CORNER_INDEX)
            {
                if (MeshPredictionSchemeParallelogram.computeParallelogramPrediction(p, corner_id, table, vertex_to_data_map, out_data, num_components, IntSpan.wrap(pred_vals[num_parallelograms])))
                {
                    // Parallelogram prediction applied and stored in
                    // |pred_vals[num_parallelograms]|
                    ++num_parallelograms;
                    // Stop processing when we reach the maximum number of allowed
                    // parallelograms.
                    if (num_parallelograms == K_MAX_NUM_PARALLELOGRAMS)
                        break;
                }
                
                
                // Proceed to the next corner attached to the vertex. First swing left
                // and if we reach a boundary, swing right from the start corner.
                if (first_pass)
                {
                    corner_id = table.swingLeft(corner_id);
                }
                else
                {
                    corner_id = table.swingRight(corner_id);
                }
                
                if (corner_id == start_corner_id)
                    break;
                if (corner_id == CornerTable.K_INVALID_CORNER_INDEX && first_pass)
                {
                    first_pass = false;
                    corner_id = table.swingRight(start_corner_id);
                }
                
            }
            
            int num_used_parallelograms = 0;
            if (num_parallelograms > 0)
            {
                for (int i = 0; i < num_components; ++i)
                {
                    multi_pred_vals[i] = 0;
                }
                
                // Check which parallelograms are actually used.
                for (int i = 0; i < num_parallelograms; ++i)
                {
                    int context = num_parallelograms - 1;
                    int pos = is_crease_edge_pos[context]++;
                    if (is_crease_edge_[context].size() <= pos)
                        throw DracoUtils.failed();
                    boolean is_crease = is_crease_edge_[context].get(pos);
                    if (!is_crease)
                    {
                        ++num_used_parallelograms;
                        for (int j = 0; j < num_components; ++j)
                        {
                            multi_pred_vals[j] = (int)((long)(multi_pred_vals[j]) + (long)(pred_vals[i][j]));
                        }
                        
                    }
                    
                }
                
            }
            
            int dst_offset = p * num_components;
            if (num_used_parallelograms == 0)
            {
                int src_offset = (p - 1) * num_components;
                this.transform_.computeOriginalValue(out_data.slice(src_offset), in_corr.slice(dst_offset), out_data.slice(dst_offset));
            }
            else
            {
                // Compute the correction from the predicted value.
                for (int c = 0; c < num_components; ++c)
                {
                    multi_pred_vals[c] /= num_used_parallelograms;
                }
                
                this.transform_.computeOriginalValue(IntSpan.wrap(multi_pred_vals), in_corr.slice(dst_offset), out_data.slice(dst_offset));
            }
            
        }
        
    }
    
    private void $initFields$()
    {
        try
        {
            is_crease_edge_ = new ArrayList[K_MAX_NUM_PARALLELOGRAMS];
            }
        catch(Exception e)
        {
            throw new RuntimeException(e);
        }
        
    }
    
}
